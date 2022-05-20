/*****************************************************************************
 * Command queue mechanism
 * (Save the number of method executing at the same time)
 *****************************************************************************/
var cordova_exec = require('cordova/exec'),
  execProxy = require('cordova/exec/proxy'),
  cordova = require('cordova'),
  common = require('./Common');

var commandQueue = [];
var _isWaitMethod = null;
var _isExecuting = false;
var _executingCnt = 0;
var MAX_EXECUTE_CNT = 10;
var _lastGetMapExecuted = 0;
var _isResizeMapExecuting = false;

// This flag becomes true when the page will be unloaded.
var _stopRequested = false;


function execCmd(success, error, pluginName, methodName, args, execOptions) {
  execOptions = execOptions || {};

  // The JavaScript special keyword 'this' indicates `who call this function`.
  // This execCmd function is executed from overlay instances such as marker.
  // So `this` means `overlay` instance.
  var overlay = this;

  // If the overlay has been already removed from map,
  // do not execute any methods on it.
  if (overlay._isRemoved && !execOptions.remove) {
    console.error('[ignore]' + pluginName + '.' + methodName + ', because removed.');
    return true;
  }

  // If the overlay is not ready in native side,
  // do not execute any methods except remove on it.
  // This code works for map class especially.
  if (!this._isReady && methodName !== 'remove') {
    console.error('[ignore]' + pluginName + '.' + methodName + ', because it\'s not ready.');
    return true;
  }

  // Push the method into the commandQueue(FIFO) at once.
  commandQueue.push({
    'execOptions': execOptions,
    'args': [
      function() {
        //-------------------------------
        // success callback
        //-------------------------------

        if (methodName === 'resizeMap') {
          _isResizeMapExecuting = false;
        }

        // Even if the method was successful,
        // but the _stopRequested flag is true,
        // do not execute further code.
        if (!_stopRequested && success) {
          var results = Array.prototype.slice.call(arguments, 0);
          common.nextTick(function() {
            success.apply(overlay,results);
          });
        }

        // Insert small delays only for `map.getMap()`,
        // because if you execute three or four `map.getMap()` at the same time,
        // Android OS itself crashes.
        // In order to prevent this error, insert small delays.
        var delay = 0;
        if (methodName === _isWaitMethod) {
          if (_isWaitMethod === 'getMap' && Date.now() - _lastGetMapExecuted < 1500) {
            delay = 1500;
          }
          _lastGetMapExecuted = Date.now();
          _isWaitMethod = null;
        }
        setTimeout(function() {
          _executingCnt--;
          common.nextTick(_exec);
        }, delay);
      },
      function() {
        //-------------------------------
        // error callback
        //-------------------------------
        if (methodName === 'resizeMap') {
          _isResizeMapExecuting = false;
        }
        if (!_stopRequested && error) {
          var results = Array.prototype.slice.call(arguments, 0);
          common.nextTick(function() {
            error.apply(overlay,results);
          });
        }

        if (methodName === _isWaitMethod) {
          _isWaitMethod = null;
        }
        _executingCnt--;
        common.nextTick(_exec);
      },
      pluginName, methodName, args]
  });

  // In order to execute all methods in safe,
  // the maps plugin limits the number of execution in a moment to 10.
  //
  // Note that Cordova-Android drops has also another internal queue,
  // and the internal queue drops our statement if the app send too much.
  //
  // Also executing too much statements at the same time,
  // it would cause many errors in native side, such as out-of-memory.
  //
  // In order to prevent these troubles, the maps plugin limits the number of execution is 10.
  if (_isExecuting || _executingCnt >= MAX_EXECUTE_CNT || _isWaitMethod || commandQueue.length === 0) {
    return;
  }
  common.nextTick(_exec);
}
function _exec() {

  // You probably wonder why there is this code because it's already simular code at the end of the execCmd function.
  //
  // Because the commandQueue might change after the last code of the execCmd.
  // (And yes, it was occurred.)
  // In order to block surely, block the execution again.
  if (_isExecuting || _executingCnt >= MAX_EXECUTE_CNT || _isWaitMethod || commandQueue.length === 0) {
    return;
  }
  _isExecuting = true;

  // Execute some execution requests (up to 10) from the commandQueue.
  var methodName;
  while (commandQueue.length > 0 && _executingCnt < MAX_EXECUTE_CNT) {
    if (!_stopRequested) {
      _executingCnt++;
    }

    // Pick up the head one.
    var commandParams = commandQueue.shift();
    methodName = commandParams.args[3];

    // If the request is `map.refreshLayout()` and another `map.refreshLayout()` is executing,
    // skip it.
    // This prevents to execute multiple `map.refreshLayout()` at the same time.
    if (methodName === 'resizeMap') {
      if (_isResizeMapExecuting) {
        _executingCnt--;
        continue;
      }
      _isResizeMapExecuting = true;
    }

    // If the `_stopRequested` flag is true,
    // do not execute any statements except `remove()` or `clear()` methods.
    if (_stopRequested && (!commandParams.execOptions.remove || methodName !== 'clear')) {
      _executingCnt--;
      continue;
    }

    // Some methods have to block other execution requests, such as `map.clear()`
    if (commandParams.execOptions.sync) {
      _isWaitMethod = methodName;
      execute(...commandParams.args);
      break;
    }

    execute(...commandParams.args);
  }

  _isExecuting = false;
  
}

const execute = function (success, fail, service, action, args) {
  var proxy = execProxy.get(service, action);

  args = args || [];

  if (proxy) {
      var callbackId = service + cordova.callbackId++;

      if (typeof success === 'function' || typeof fail === 'function') {
          cordova.callbacks[callbackId] = { success: success, fail: fail };
      }
      try {
          // callbackOptions param represents additional optional parameters command could pass back, like keepCallback or
          // custom callbackId, for example {callbackId: id, keepCallback: true, status: cordova.callbackStatus.JSON_EXCEPTION }
          var onSuccess = function (result, callbackOptions) {
              callbackOptions = callbackOptions || {};
              var callbackStatus;
              // covering both undefined and null.
              // strict null comparison was causing callbackStatus to be undefined
              // and then no callback was called because of the check in cordova.callbackFromNative
              // see CB-8996 Mobilespec app hang on windows
              if (callbackOptions.status !== undefined && callbackOptions.status !== null) {
                  callbackStatus = callbackOptions.status;
              } else {
                  callbackStatus = cordova.callbackStatus.OK;
              }
              cordova.callbackSuccess(callbackOptions.callbackId || callbackId,
                  {
                      status: callbackStatus,
                      message: result,
                      keepCallback: callbackOptions.keepCallback || false
                  });
          };
          var onError = function (err, callbackOptions) {
              callbackOptions = callbackOptions || {};
              var callbackStatus;
              // covering both undefined and null.
              // strict null comparison was causing callbackStatus to be undefined
              // and then no callback was called because of the check in cordova.callbackFromNative
              // note: status can be 0
              if (callbackOptions.status !== undefined && callbackOptions.status !== null) {
                  callbackStatus = callbackOptions.status;
              } else {
                  callbackStatus = cordova.callbackStatus.OK;
              }
              cordova.callbackError(callbackOptions.callbackId || callbackId,
                  {
                      status: callbackStatus,
                      message: err,
                      keepCallback: callbackOptions.keepCallback || false
                  });
          };
          proxy(onSuccess, onError, args);
      } catch (e) {
          console.log('[GoogleMaps] Exception calling native with command :: ' + service + ' :: ' + action + ' ::exception=' + e);
      }
  } else {
      console.log('[GoogleMaps] Error: exec proxy not found for :: ' + service + ' :: ' + action);

      if (typeof fail === 'function') {
          fail('Missing Command Error');
      }
  }
};


//----------------------------------------------------
// Stop all executions if the page will be closed.
//----------------------------------------------------
function stopExecution() {
  // Request stop all tasks.
  _stopRequested = true;
}
window.addEventListener('unload', stopExecution);

module.exports = execCmd;
