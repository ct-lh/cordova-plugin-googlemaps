var common = require('./Common'),
    execProxy = require('cordova/exec/proxy');

/*****************************************************************************
 * Config Class
 *****************************************************************************/
var Environment = {};

Environment.setBackgroundColor = function (color) {
  execProxy.get('PluginEnvironment', 'setBackGroundColor')(() => {}, () => {}, [common.HTMLColor2RGBA(color)]);
};

Environment.isAvailable = function (callback) {
  execProxy.get('PluginEnvironment',  'isAvailable')(function () {
    if (typeof callback === 'function') {
      callback(true);
    }
  }, function (message) {
    if (typeof callback === 'function') {
      callback(false, message);
    }
  }, ['']);
};

Environment.getLicenseInfo = function (callback) {
  execProxy.get('PluginEnvironment', 'getLicenseInfo')(function (txt) {
    callback(txt);
  }, () => {}, []);
};

Environment.setEnv = function (options) {
  if (options) {
    execProxy.get('PluginEnvironment', 'setEnv')(() => {}, () => {}, [options]);
  }
};

module.exports = Environment;
