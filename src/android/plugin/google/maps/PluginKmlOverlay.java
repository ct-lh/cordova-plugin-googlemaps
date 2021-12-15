package plugin.google.maps;

import android.os.Bundle;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;


public class PluginKmlOverlay extends MyPlugin implements MyPluginInterface {
  private String Tag = "PluginKmlOverlay";
  private HashMap<String, Bundle> styles = new HashMap<String, Bundle>();

  private enum KML_TAG {
    NOT_SUPPORTED,

    kml,
    style,
    styleurl,
    stylemap,
    schema,
    coordinates
  }

  /**
   * Create kml overlay
   *
   * @param args
   * @param callbackContext
   * @throws JSONException
   */
  public void create(final JSONArray args, final CallbackContext callbackContext) throws JSONException {

    final JSONObject opts = args.getJSONObject(1);
    self = this;
    if (!opts.has("url")) {
      callbackContext.error("No kml file is specified");
      return;
    }

    cordova.getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {

        String urlStr = null;

        try {
          urlStr = opts.getString("url");
        } catch (JSONException e) {
          Log.e(Tag, e.getMessage());
          e.printStackTrace();
        }
        if (urlStr == null || urlStr.length() == 0) {
          callbackContext.error("No kml file is specified");
          return;
        }

        String currentPageUrl = webView.getUrl();
        if (!urlStr.contains("://") &&
            !urlStr.startsWith("/") &&
            !urlStr.startsWith("www/") &&
            !urlStr.startsWith("data:image") &&
            !urlStr.startsWith("./") &&
            !urlStr.startsWith("../")) {
          urlStr = "./" + urlStr;
        }

        if (currentPageUrl.startsWith("http://localhost") ||
            currentPageUrl.startsWith("http://127.0.0.1")) {
          if (urlStr.contains("://")) {
            urlStr = urlStr.replaceAll("http://.+?/", "file:///android_asset/www/");
          } else {
            // Avoid WebViewLocalServer (because can not make a connection for some reason)
            urlStr = "file:///android_asset/www/".concat(urlStr);
          }
        }


        if (urlStr.startsWith("./")  || urlStr.startsWith("../")) {
          urlStr = urlStr.replace("././", "./");
          currentPageUrl = currentPageUrl.replaceAll("[^\\/]*$", "");
          urlStr = currentPageUrl + "/" + urlStr;
        }
        if (urlStr.startsWith("cdvfile://")) {
          urlStr = PluginUtil.getAbsolutePathFromCDVFilePath(webView.getResourceApi(), urlStr);
        }

        // Avoid WebViewLocalServer (because can not make a connection for some reason)
        if (urlStr.contains("http://localhost") || urlStr.contains("http://127.0.0.1")) {
          urlStr = urlStr.replaceAll("^http://[^\\/]+\\//", "file:///android_asset/www/");
        }


        final String finalUrl = urlStr;
        executorService.submit(new Runnable() {
          @Override
          public void run() {
            Bundle result = loadKml(finalUrl);
            callbackContext.success(PluginUtil.Bundle2Json(result));
          }
        });
      }
    });
  }

  private Bundle loadKml(String urlStr) {

    InputStream inputStream = getKmlContents(urlStr);
    if (inputStream == null) {
      return null;
    }
    try {
      // KMZ is not ready yet.... sorry

//      if (urlStr.contains(".kmz")) {
//        String cacheDirPath = cordova.getActivity().getCacheDir() + "/" + Integer.toString(urlStr.hashCode(), 16);
//        File cacheDir = new File(cacheDirPath);
//        if (!cacheDir.exists()) {
//          cacheDir.mkdirs();
//        }
//        ArrayList<File> files =  (PluginUtil.unpackZipFromBytes(inputStream, cacheDirPath));
//        inputStream.close();
//        for (File file : files) {
//          if (file.getName().contains(".kml")) {
//            inputStream = new FileInputStream(file);
//            break;
//          }
//        }
//      }

      DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder docBuilder = docBuilderFactory.newDocumentBuilder();
      Document document = docBuilder.parse(inputStream);

      KmlParserClass parser = new KmlParserClass();
      Bundle root = parser.parseXml(document.getDocumentElement());
      Bundle result = new Bundle();
      result.putBundle("schemas", parser.schemaHolder);
      result.putBundle("styles", parser.styleHolder);
      result.putBundle("root", root);

      inputStream.close();
      inputStream = null;
      return result;
    } catch (Exception e) {
      Log.e(Tag, e.getMessage());
      e.printStackTrace();
      return null;
    }
  }

  class KmlParserClass {
    private String Tag = "KmlParserClass";
    public Bundle styleHolder = new Bundle();
    public Bundle schemaHolder = new Bundle();


    private Bundle parseXml(Node rootNode) {
      // Exclude text nodes
      if (rootNode != null && rootNode.getNodeType() == Node.TEXT_NODE) {
        return null;
      }

      Bundle result = new Bundle();
      String styleId, schemaId, txt;
      int i;
      String tagName = rootNode.getNodeName().toLowerCase();
      Node childNode;
      Bundle styles, schema, extendedData;
      ArrayList<Bundle> children;
      ArrayList<String> styleIDs;

      result.putString("tagName", tagName);

      KML_TAG kmlTag = null;
      try {
        kmlTag = KML_TAG.valueOf(tagName);
      } catch(Exception e) {
//        Log.e(Tag, e.getMessage());
        kmlTag = KML_TAG.NOT_SUPPORTED;
      }

      NamedNodeMap attributes = rootNode.getAttributes();

      if (attributes != null) {
        for (int j = 0; j < attributes.getLength(); j++) {
          Attr attr = (Attr) attributes.item(j);
          result.putString(attr.getNodeName(), attr.getNodeValue());
        }
      }

      switch (kmlTag) {

        case styleurl:
          styleId = rootNode.getTextContent();
          result.putString("styleId", styleId);
          break;

        case stylemap:
        case style:

          // Generate a style id for the tag
          Attr styleAttr = (Attr) rootNode.getAttributes().getNamedItem("id");
          styleId = styleAttr != null ? styleAttr.getValue() : null;
          if (styleId == null || styleId.isEmpty()) {
            styleId = "__" + rootNode + "__";
          }
          result.putString("styleId", styleId);

          // Store style information into the styleHolder.
          styles = new Bundle();
          children = new ArrayList<Bundle>();
          childNode = rootNode.getFirstChild();

          while (childNode != null) {
            Bundle node = this.parseXml(childNode);
            if (node != null) {
              if (node.containsKey("value")) {
                styles.putString(node.getString("tagName"), node.getString("value"));
              } else {
                children.add(node);
              }
            }
            childNode = childNode.getNextSibling();
          }
          if (children.size() > 0) {
            styles.putParcelableArrayList("children", children);
          }
          styleHolder.putBundle(styleId, styles);


          break;

        case schema:

          // Generate a schema id for the tag
          Attr schemaAttr = (Attr) rootNode.getAttributes().getNamedItem("id");
          schemaId = schemaAttr != null ? schemaAttr.getValue() : null;
          if (schemaId == null || schemaId.isEmpty()) {
            schemaId = "__" + rootNode + "__";
          }

          // Store schema information into the schemaHolder.
          schema = new Bundle();

          Attr schemaNameAttr = (Attr) rootNode.getAttributes().getNamedItem("name");
          schema.putString("name", schemaNameAttr != null ? schemaNameAttr.getValue() : "");
          children = new ArrayList<Bundle>();
          childNode = rootNode.getFirstChild();
          while (childNode != null) {
            Bundle node = this.parseXml(childNode);
            if (node != null) {
              children.add(node);
            }
            childNode = childNode.getNextSibling();
          }
          if (children.size() > 0) {
            schema.putParcelableArrayList("children", children);
          }
          schemaHolder.putBundle(schemaId, schema);


          break;
        case coordinates:


          ArrayList<Bundle> latLngList = new ArrayList<Bundle>();

          txt = rootNode.getTextContent();
          txt = txt.replaceAll("\\s+", "\n");
          txt = txt.replaceAll("\\n+", "\n");
          String lines[] = txt.split("\n");
          String tmpArry[];
          Bundle latLng;
          for (i = 0; i < lines.length; i++) {
            lines[i] = lines[i].replaceAll("[^0-9,.\\-Ee]", "");
            if (!"".equals(lines[i])) {
              tmpArry = lines[i].split(",");
              latLng = new Bundle();
              latLng.putDouble("lat", Double.parseDouble(tmpArry[1]));
              latLng.putDouble("lng", Double.parseDouble(tmpArry[0]));
              latLngList.add(latLng);
            }
          }

          result.putParcelableArrayList(tagName, latLngList);
          break;



        default:

          childNode = rootNode.getFirstChild();
          boolean hasMoreThanText = false;

          while (childNode != null) {
            if (childNode.getNodeType() != Node.TEXT_NODE) {
              hasMoreThanText = true;
              break;
            }
            childNode = childNode.getNextSibling();
          }

          if (hasMoreThanText) {
            childNode = rootNode.getFirstChild();
            children = new ArrayList<Bundle>();

            while (childNode != null) {
              Bundle node = this.parseXml(childNode);
              if (node != null) {
                if (node.containsKey("styleId")) {
                  //--------------------------------------------
                  // writtin in JavaScript
                  // result.styleIDs = result.styleIDs || [];
                  // result.styleIDs.push(node.styleId);
                  //--------------------------------------------
                  styleIDs = result.getStringArrayList("styleIDs");
                  if (styleIDs == null) {
                    styleIDs = new ArrayList<String>();
                  }
                  styleIDs.add(node.getString("styleId"));
                  result.putStringArrayList("styleIDs", styleIDs);
                } else if (!("schema".equals(node.getString("tagName")))) {
                  children.add(node);
                }
              }
              childNode = childNode.getNextSibling();
            }

            result.putParcelableArrayList("children", children);
          } else {
            result.putString("value", rootNode.getTextContent());
          }
          break;
      }

      return result;
    }
  }


  private InputStream getKmlContents(String urlStr) {

    InputStream inputStream;
    try {
//      Log.e("PluginKmlOverlay", "---> url = " + urlStr);
      if (urlStr.startsWith("http://") || urlStr.startsWith("https://")) {
        URL url = new URL(urlStr);
        boolean redirect = true;
        HttpURLConnection http = null;
        String cookies = null;
        int redirectCnt = 0;
        while(redirect && redirectCnt < 10) {
          redirect = false;
          http = (HttpURLConnection)url.openConnection();
          http.setRequestMethod("GET");
          if (cookies != null) {
            http.setRequestProperty("Cookie", cookies);
          }
          http.addRequestProperty("Accept-Language", "en-US,en;q=0.8");
          http.addRequestProperty("User-Agent", "Mozilla");
          http.setInstanceFollowRedirects(true);
          HttpURLConnection.setFollowRedirects(true);

          // normally, 3xx is redirect
          int status = http.getResponseCode();
          if (status != HttpURLConnection.HTTP_OK) {
            if (status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_SEE_OTHER)
              redirect = true;
          }
          if (redirect) {
            // get redirect url from "location" header field
            url = new URL(http.getHeaderField("Location"));

            // get the cookie if need, for login
            cookies = http.getHeaderField("Set-Cookie");

            // Disconnect the current connection
            http.disconnect();
            redirectCnt++;
          }
        }

        inputStream = http.getInputStream();
      } else if (urlStr.indexOf("file://") == 0 && !urlStr.contains("file:///android_asset/") ||
          urlStr.indexOf("/") == 0) {
        urlStr = urlStr.replace("file://", "");
        try {
          boolean isAbsolutePath = urlStr.startsWith("/");
          File relativePath = new File(urlStr);
          urlStr = relativePath.getCanonicalPath();
          if (!isAbsolutePath) {
            urlStr = urlStr.substring(1);
          }
        } catch (Exception e) {
          Log.e(Tag, e.getMessage());
          e.printStackTrace();
        }
//        Log.e("PluginKmlOverlay", "---> url = " + urlStr);
        inputStream = new FileInputStream(urlStr);
      } else {
        if (urlStr.indexOf("file:///android_asset/") == 0) {
          urlStr = urlStr.replace("file:///android_asset/", "");
        }


        try {
          boolean isAbsolutePath = urlStr.startsWith("/");
          File relativePath = new File(urlStr);
          urlStr = relativePath.getCanonicalPath();
          if (!isAbsolutePath) {
            urlStr = urlStr.substring(1);
          }
        } catch (Exception e) {
          Log.e(Tag, e.getMessage());
          e.printStackTrace();
        }
//        Log.e("PluginKmlOverlay", "---> url = " + urlStr);
        inputStream = cordova.getActivity().getResources().getAssets().open(urlStr);
      }

    } catch (Exception e) {
      Log.e(Tag, e.getMessage());
      e.printStackTrace();
      return null;
    }

    return inputStream;

  }

}
