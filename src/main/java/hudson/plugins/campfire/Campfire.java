package hudson.plugins.campfire;

import hudson.ProxyConfiguration;
import hudson.model.Hudson;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLProtocolException;
import javax.xml.parsers.*;
import javax.xml.xpath.*;

import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.*;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class Campfire {
    private String subdomain;
    private String token;
    private boolean ssl;

    public Campfire(String subdomain, String token, boolean ssl) {
        super();
        this.subdomain = subdomain;
        this.token = token;
        this.ssl = ssl;
    }

    protected HttpClient getClient() {
      HttpClient client = new HttpClient();
      Credentials defaultcreds = new UsernamePasswordCredentials(this.token, "x");
      client.getState().setCredentials(new AuthScope(getHost(), -1, AuthScope.ANY_REALM), defaultcreds);
      client.getParams().setAuthenticationPreemptive(true);
      client.getParams().setParameter("http.useragent", "Mozilla/5.0 (Macintosh; U; Intel Mac OS X 10_6_4; en-us) AppleWebKit/533.16 (KHTML, like Gecko) Version/5.0 Safari/533.16");
      ProxyConfiguration proxy = Hudson.getInstance().proxy;
      if (proxy != null) {
          client.getHostConfiguration().setProxy(proxy.name, proxy.port);
      }
      return client;
    }

    protected String getHost() {
      return this.subdomain + ".campfirenow.com";
    }

    public String getSubdomain() {
      return this.subdomain;
    }

    public String getToken() {
      return this.token;
    }

    protected String getProtocol() {
      if (this.ssl) { return "https://"; }
      return "http://";
    }

    public int post(String url, String body) {
        PostMethod post = new PostMethod(getProtocol() + getHost() + "/" + url);
        post.setRequestHeader("Content-Type", "application/xml");
        try {
            post.setRequestEntity(new StringRequestEntity(body, "application/xml", "UTF8"));
            return getClient().executeMethod(post);
        } catch (SSLProtocolException e) {
            return 200; // ignore
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            post.releaseConnection();
        }
    }

    public String get(String url) {
        GetMethod get = new GetMethod(getProtocol() + getHost() + "/" + url);
        get.setFollowRedirects(true);
        get.setRequestHeader("Content-Type", "application/xml");
        try {
            getClient().executeMethod(get);
            verify(get.getStatusCode());
            return get.getResponseBodyAsString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            get.releaseConnection();
        }
    }

    public boolean verify(int returnCode) {
        if (returnCode != 200) {
            throw new RuntimeException("Unexpected response code: " + Integer.toString(returnCode));
        }
        return true;
    }

    private List<Room> getRooms(){
        String body = get("rooms.xml");

        List<Room> rooms;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            StringReader reader = new StringReader(body);
            InputSource inputSource = new InputSource( reader );
            Document doc = builder.parse(inputSource);

            XPath xpath = XPathFactory.newInstance().newXPath();
            XPathExpression roomExpr = xpath.compile("//room");
            XPathExpression nameExpr = xpath.compile(".//name");
            XPathExpression idExpr = xpath.compile(".//id");

            NodeList roomNodeList = (NodeList) roomExpr.evaluate(doc, XPathConstants.NODESET);
            rooms = new ArrayList<Room>();
            for (int i = 0; i < roomNodeList.getLength(); i++) {
                Node roomNode = roomNodeList.item(i);
                String name = ((NodeList) nameExpr.evaluate(roomNode, XPathConstants.NODESET)).item(0).getFirstChild().getNodeValue();
                String id = ((NodeList) idExpr.evaluate(roomNode, XPathConstants.NODESET)).item(0).getFirstChild().getNodeValue();
                rooms.add(new Room(this, name.trim(), id.trim()));
            }
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        } catch (SAXException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (XPathExpressionException e) {
            throw new RuntimeException(e);
        }
        return rooms;
    }

    public Room findRoomByName(String name) {
        Pattern pattern = Pattern.compile("^[0-9]+$");
        Matcher matcher = pattern.matcher(name);
        if (matcher.find()) {
            return new Room(this,name,name);
        } else {
            for (Room room : getRooms()) {
                if (room.getName().equals(name)) {
                    return room;
                }
            }
            return null;
        }
    }

    private Room createRoom(String name) {
        verify(post("rooms.xml", "<request><room><name>" + name + "</name><topic></topic></room></request>"));
        return findRoomByName(name);
    }

    public Room findOrCreateRoomByName(String name) {
        Room room = findRoomByName(name);
        if (room != null) {
            return room;
        }
        return createRoom(name);
    }
}
