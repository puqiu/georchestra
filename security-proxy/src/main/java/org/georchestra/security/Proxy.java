/*
 * Copyright (C) 2009-2018 by the geOrchestra PSC
 *
 * This file is part of geOrchestra.
 *
 * geOrchestra is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * geOrchestra is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * geOrchestra.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.georchestra.security;

import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DeflaterInputStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpTrace;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;
import org.apache.http.message.BasicNameValuePair;
import org.georchestra.commons.configuration.GeorchestraConfiguration;
import org.georchestra.ogcservstatistics.log4j.OGCServiceMessageFormatter;
import org.georchestra.security.permissions.Permissions;
import org.georchestra.security.permissions.UriMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.security.cas.ServiceProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.Closer;

/**
 * This proxy provides an indirect access to a remote host to retrieve data.
 * Useful to overcome security constraints on client side.
 * <p>
 * There are two primary ways that the paths can be encoded:
 * <ul>
 * <li>The full url to forward to is encoded in a parameter called "url"</li>
 * <li>The url is encoded as part of the path. Then the target should be
 * defined (either in the targets-mapping.properties file of the datadir or in
 * the targets map property of the proxyservlet.xml file)</li>
 * </ul>
 * Examples:
 * <p>
 * Assume the default target is http://xyz.com and the targets are:
 * x:http://x.com, y:https://y.com
 * </p>
 * <ul>
 * <li>http://this.com/context/path -- gives -- http://xyz.com/path</li>
 * <li>http://this.com/context/x/path -- gives -- http://x.com/path</li>
 * <li>http://this.com/context/y/path -- gives -- https://y.com/path</li>
 * </ul>
 * </li>
 * </ul>
 * </p>
 *
 * @author yoann.buch@gmail.com
 * @author jesse.eichar@camptocamp.com
 */
@Controller
public class Proxy {
    protected static final Log logger = LogFactory.getLog(Proxy.class.getPackage().getName());
    protected static final Log statsLogger = LogFactory.getLog(Proxy.class.getPackage().getName() + ".statistics");
    protected static final Log commonLogger = LogFactory.getLog(Proxy.class.getPackage().getName() + ".statistics-common");

    @Autowired
    private GeorchestraConfiguration georchestraConfiguration;

    /**
     * must be defined
     */
    private String defaultTarget;
    private String publicHostname = "https://georchestra.mydomain.org";

    private Map<String, String> targets = Collections.emptyMap();
    private HeadersManagementStrategy headerManagement = new HeadersManagementStrategy();
    private FilterRequestsStrategy strategyForFilteringRequests = new AcceptAllRequests();
    private List<String> requireCharsetContentTypes = Collections.emptyList();
    private String defaultCharset = "UTF-8";

    private RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();
    private ServicesMonitoring servicesMonitoring = null;

    private Permissions proxyPermissions = null;
    private Permissions sameDomainPermissions;
    private String proxyPermissionsFile;

    private Integer httpClientTimeout = 300000;

    private final static String setCookieHeader = "Set-Cookie";

    public void setHttpClientTimeout(Integer timeout) {
        this.httpClientTimeout = timeout;
    }

    public void setPublicHostname(String publicHostname) {
        this.publicHostname = publicHostname;
    }

    public Integer getHttpClientTimeout() {
        return httpClientTimeout;
    }

    public void init() throws Exception {

        if (targets != null) {
            for (String url : targets.values()) {
                new URL(url); // test that it is a valid URL
            }
        }

        // georchestra datadir autoconfiguration
        // dependency injection / properties setter() are made by Spring before
        // init() call
        if ((georchestraConfiguration != null) && (georchestraConfiguration.activated())) {
            logger.info("geOrchestra configuration detected, reconfiguration in progress ...");

            Properties pTargets = georchestraConfiguration.loadCustomPropertiesFile("targets-mapping");

            this.targets.clear();
            for (String target : pTargets.stringPropertyNames()) {
                this.targets.put(target, pTargets.getProperty(target));
            }

            // Configure proxy permissions based on proxy-permissions.xml file in datadir
            String datadirContext = georchestraConfiguration.getContextDataDir();
            File datadirPermissionsFile = new File(String.format("%s%s%s", datadirContext,
                    File.separator, "proxy-permissions.xml"));
            if(datadirPermissionsFile.exists()){
                logger.info("reading proxy permissions from " + datadirPermissionsFile.getAbsolutePath());
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(datadirPermissionsFile);
                    setProxyPermissions(Permissions.Create(fis));
                    fis.close();
                } catch(Exception ex){
                      logger.error("Error during proxy permissions configuration from "
                              + datadirPermissionsFile.getAbsolutePath());
                } finally {
                    fis.close();
                }
            }

            logger.info("Done.");
        }

        // Create a deny permission for URL with same domain
        String publicDomain = new URL(this.publicHostname).getHost();
        this.sameDomainPermissions = new Permissions();
        this.sameDomainPermissions.setDenied(Collections.singletonList(new UriMatcher().setDomain(publicDomain)));
        this.sameDomainPermissions.setAllowByDefault(true);
        this.sameDomainPermissions.init();

        this.servicesMonitoring = new ServicesMonitoring(targets);

        // Proxy permissions not set by datadir
        if (proxyPermissionsFile != null && proxyPermissions == null) {
            Closer closer = Closer.create();
            try {
                final ClassLoader classLoader = Proxy.class.getClassLoader();
                InputStream inStream = closer.register(classLoader.getResourceAsStream(proxyPermissionsFile));
                if (inStream == null) {
                    throw new RuntimeException("ProxyPermissionsFile not found");
                }
                setProxyPermissions(Permissions.Create(inStream));
            } finally {
                closer.close();
            }
        }
    }

    /* ---------- start work around for no gateway option -------------- */
    private Gateway gateway = new Gateway();

    @RequestMapping(value = "/gateway", method = { GET, POST })
    public void gateway(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        gateway.loadCredentialsPage(request, response);
    }

    @RequestMapping(value = "/testPage", method = { GET })
    public void testPage(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        gateway.testPage(response);
    }

    /* ---------- end work around for no gateway option -------------- */

    @RequestMapping(value= "/**", params = "login", method = { GET, POST })
    public void login(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException, URISyntaxException {
        String uri = request.getRequestURI();

        URIBuilder uriBuilder = new URIBuilder(uri);
        Enumeration parameterNames = request.getParameterNames();
        while (parameterNames.hasMoreElements()) {
            String paramName = (String) parameterNames.nextElement();
            if (!"login".equals(paramName)) {
                String[] paramValues = request.getParameterValues(paramName);
                for (int i = 0; i < paramValues.length; i++) {
                    uriBuilder.setParameter(paramName, paramValues[i]);
                }
            }
        }

        redirectStrategy.sendRedirect(request, response, uriBuilder.build().toString());
    }

    /**
     * Entrypoint used for monitoring purposes.
     *
     * @param request
     * @param response
     * @throws IOException
     */
    @RequestMapping("/services_monitoring")
    public void servicesMonitoring(HttpServletRequest request, HttpServletResponse response) throws IOException {
        this.servicesMonitoring.checkServices(request, response);
    }

    /**
     * Entrypoint used for login.
     *
     * @param request
     * @param response
     * @param sURL
     * @throws ServletException
     * @throws IOException
     */
    @RequestMapping(value = "/**", params = { "login", "url" }, method = { GET, POST })
    public void login(HttpServletRequest request, HttpServletResponse response, @RequestParam("url") String sURL) throws ServletException, IOException {
        redirectStrategy.sendRedirect(request, response, sURL);
    }

    /**
     * Entry point used mainly for XHR requests using a URL-encoded parameter named url.
     * @param request
     * @param response
     * @param sURL
     * @throws IOException
     */
    @RequestMapping(value ="/proxy/", params = { "url", "!login" })
    public void handleUrlParamRequest(HttpServletRequest request, HttpServletResponse response, @RequestParam("url") String sURL) throws IOException {
        testLegalContentType(request);
        URL url;
        try {
            url = new URL(sURL);
        } catch (MalformedURLException e) { // not an url
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        }

        /*
         * - deny request with same domain as public_host - deny based on
         * proxy-permissions.xml file
         */
        if (this.sameDomainPermissions.isDenied(url) || proxyPermissions.isDenied(url)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "URL is not allowed.");
            return;
        }
        handleRequest(request, response, sURL, false);
    }

    /**
     * Entry point used for security-proxified webapps. Note: the url parameter is sometimes used
     * by the underlying webapps (e.g. mapfishapp and the mfprint configuration). hence we need
     * to allow it in the following "params" array.
     *
     * @param request
     * @param response
     * @throws IOException
     */
    @RequestMapping(value = "/**", params = { "!login" })
    public void handleRequest(HttpServletRequest request, HttpServletResponse response) {
        handlePathEncodedRequests(request, response);
    }

    /**
     * Default redirection to defaultTarget. By default returns a 302 redirect to '/header/'. The
     * parameter can be customized in the security-proxy.properties file.
     *
     * @param request
     * @param response
     * @throws IOException
     */
    @RequestMapping(value = "/", params = { "!url", "!login" })
    public void handleDefaultRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.sendRedirect(defaultTarget);
        return;
    }
    
    /**
     * Indicates whether the requested URL is a one protected by the
     * Security-proxy or not, e.g. urlIsProtected(mapfishapp) will generally
     * return true (unless if mapfishapp is not configured on this geOrchestra
     * instance, which is probably unlikely).
     *
     * @param request
     *            the HttpServletRequest
     * @param url
     *            the requested url
     * @return true if the url is protected by the SP, false otherwise.
     *
     * @throws IOException
     */
    private boolean urlIsProtected(HttpServletRequest request, URL url) throws IOException {
        if (isSameServer(request, url)) {
            String requestURI = url.getPath();
            String[] requestSegments = splitRequestPath(requestURI);
            for (String target : targets.values()) {
                if (samePathPrefix(requestSegments, target)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check if the request targets a host (host header) which is the same as the requested URL in parameter.
     *
     * @param request
     * @param url
     * @return true if the host in the request matches the host in the requested URL, false otherwise.
     */
    private boolean isSameServer(HttpServletRequest request, URL url) {
        try {
            return InetAddress.getByName(request.getServerName()).equals(InetAddress.getByName(url.getHost()));
        } catch (UnknownHostException e) {
            logger.error("Unknown host: " + request.getServerName());
            return false;
        }
    }

    /**
     * Check if the target url matches a security-proxified target.
     *
     * @param requestSegments
     * @param target
     * @return true if so, false otherwise.
     * @throws MalformedURLException
     */
    private boolean samePathPrefix(String[] requestSegments, String target) throws MalformedURLException {
        String[] targetSegments = splitRequestPath(new URL(target).getPath());
        for (int i = 0; i < targetSegments.length; i++) {
            String targetSegment = targetSegments[i];
            if (!targetSegment.equals(requestSegments[i])) {
                return false;
            }
        }
        return true;
    }

    private String[] splitRequestPath(String requestURI) {
        String[] requestSegments;
        if (requestURI.charAt(0) == '/') {
            requestSegments = StringUtils.split(requestURI.substring(1), '/');
        } else {
            requestSegments = StringUtils.split(requestURI, '/');
        }
        return requestSegments;
    }

    /**
     * Since the URL param can access any url we need to control what it can
     * request so it is not used for nefarious purposes. We are basing the
     * control on contentType because it is supposed to be able to access any
     * server.
     */
    private void testLegalContentType(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return;
        }
        // focus only on type, not on the text encoding
        String type = contentType.split(";")[0];
        for (String validTypeContent : requireCharsetContentTypes) {
            if (!validTypeContent.equals(type)) {
                return;
            }
        }
        throw new IllegalArgumentException("ContentType " + contentType
                + " is not permitted to be requested when the request is made through the URL parameter form.");
    }

    // ----------------- Implementation methods ----------------- //

    private String buildForwardRequestURL(HttpServletRequest request) {
        String forwardRequestURI = request.getRequestURI();
        forwardRequestURI = forwardRequestURI.replaceAll("//", "/");

        return forwardRequestURI;
    }

    /**
     * Main entry point for methods where the request path is encoded in the
     * path of the URL
     */
    private void handlePathEncodedRequests(HttpServletRequest request, HttpServletResponse response) {
        try {
            String contextPath = request.getServletPath() + request.getContextPath();
            String forwardRequestURI = buildForwardRequestURL(request);
            HttpMethod type = HttpMethod.resolve(request.getMethod());
            logger.debug("handlePathEncodedRequests: -- Handling Request: " + type + ":" + forwardRequestURI + " from: " + request.getRemoteAddr());

            String sURL = findTarget(forwardRequestURI);

            if (sURL == null) {
                response.sendError(404);
                return;
            }

            URL url;
            try {
                url = new URL(sURL);
            } catch (MalformedURLException e) {
                throw new MalformedURLException(sURL + " is not a valid URL");
            }

            boolean sameHostAndPort = false;

            try {
                sameHostAndPort = isSameHostAndPort(request, url);
            } catch (UnknownHostException e) {
                logger.error("Unknown host in requested URL", e);
                response.sendError(503);
                return;
            }

            if (sameHostAndPort && (isRecursiveCallToProxy(forwardRequestURI, contextPath) || isRecursiveCallToProxy(url.getPath(), contextPath))) {
                response.sendError(403, forwardRequestURI + " is a recursive call to this service.  That is not a legal request");
            }

            final String query = request.getQueryString();
            boolean needCasValidation =  (request.getParameter(ServiceProperties.DEFAULT_CAS_ARTIFACT_PARAMETER) != null)
                    && (request.getUserPrincipal() == null)
                    && urlIsProtected(request, new URL(sURL));
            // special case: if we have a ticket parameter and no
            // authentication principal, we probably need to validate/open
            // the session against CAS server
            if (needCasValidation) {
                // loginUrl: sends a redirect to the client with a ?login (or &login if other arguments)
                // since .*login patterns are protected by the SP, this would trigger an authentication
                // onto CAS (which should succeed if the user is already connected onto the platform).
                String loginUrl = String.format("%s%s%s", request.getPathInfo(),
                        StringUtils.isEmpty(query) ? "?" : query, "login");
                redirectStrategy.sendRedirect(request, response, loginUrl);
                return;
            }
            if(query != null)
                sURL += "?" + query;
            handleRequest(request, response, sURL, true);
        } catch (IOException e) {
            logger.error("Error connecting to client", e);
        }
    }

    private boolean isSameHostAndPort(HttpServletRequest request, URL url) throws IOException {
        return isSameServer(request, url) && url.getPort() == request.getServerPort();
    }

    private String findTarget(String requestURI) {
        String[] segments;
        if (requestURI.charAt(0) == '/') {
            segments = requestURI.substring(1).split("/");
        } else {
            segments = requestURI.split("/");
        }

        if (segments.length == 0) {
            return null;
        }
        String target = targets.get(segments[0]);
        if (target == null) {
            return null;
        } else {
            StringBuilder builder = new StringBuilder("/");
            for (int i = 1; i < segments.length; i++) {
                String segment = segments[i];
                builder.append(segment);
                if (i + 1 < segments.length)
                    builder.append("/");
            }

            if (requestURI.endsWith("/") && builder.charAt(builder.length() - 1) != '/') {
                builder.append('/');
            }

            return concat(target, builder);
        }
    }

    private String concat(String target, StringBuilder builder) {
        if (target == null) {
            return null;
        }
        String target2 = target;
        if (target.endsWith("/")) {
            target2 = target.substring(0, target.length() - 1);
        }
        if (builder.charAt(0) != '/') {
            builder.insert(0, '/');
        }
        return target2 + builder;

    }

    private String findMatchingTarget(HttpServletRequest request) {
        String requestURI = buildForwardRequestURL(request);
        String[] segments = splitRequestPath(requestURI);

        if (segments.length == 0) {
            return null;
        }

        if (targets.containsKey(segments[0])) {
            return segments[0];
        } else {
            return null;
        }
    }

    /**
     * Actually do the request to the proxified server.
     *
     * @param request the original request
     * @param finalResponse the servlet response
     * @param sURL the url to proxify onto
     * @param localProxy true if the request targets a security-proxyfied webapp (e.g. mapfishapp, ...), false otherwise
     */
    private void handleRequest(HttpServletRequest request, HttpServletResponse finalResponse, String sURL, boolean localProxy) {
        HttpClientBuilder htb = HttpClients.custom().disableRedirectHandling();

        RequestConfig config = RequestConfig.custom().setSocketTimeout(this.httpClientTimeout).build();
        htb.setDefaultRequestConfig(config);

        //
        // Handle http proxy for external request.
        // Proxy must be configured by system variables (e.g.: -Dhttp.proxyHost=proxy -Dhttp.proxyPort=3128)
        htb.setRoutePlanner(new SystemDefaultRoutePlanner(ProxySelector.getDefault()));
        CloseableHttpClient httpclient = htb.build();

        HttpResponse proxiedResponse = null;
        int statusCode = 500;

        try {
            URL url = null;
            try {
                url = new URL(sURL);
            } catch (MalformedURLException e) { // not an url
                finalResponse.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
                return;
            }

            // HTTP protocol is required
            if (!"http".equalsIgnoreCase(url.getProtocol()) && !"https".equalsIgnoreCase(url.getProtocol())) {
                finalResponse.sendError(HttpServletResponse.SC_BAD_REQUEST, "HTTP protocol expected. \"" + url.getProtocol() + "\" used.");
                return;
            }

            // check if proxy must filter on final host
            if (!strategyForFilteringRequests.allowRequest(url)) {
                finalResponse.sendError(HttpServletResponse.SC_BAD_REQUEST, "Host \"" + url.getHost() + "\" is not allowed to be requested");
                return;
            }

            logger.debug("Final request -- " + sURL);


            HttpRequestBase proxyingRequest = makeRequest(request, sURL);
            headerManagement.configureRequestHeaders(request, proxyingRequest, localProxy);

            try {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                Header[] originalHeaders = proxyingRequest.getHeaders("sec-orgname");
                String org = "";
                for (Header originalHeader : originalHeaders) {
                    org = originalHeader.getValue();
                }
                // no OGC SERVICE log if request going through /proxy/?url=
                if (!request.getRequestURI().startsWith("/proxy/")) {
                    String [] roles = new String[] {""};
                    try {
                        Header[] rolesHeaders = proxyingRequest.getHeaders("sec-roles");
                        if (rolesHeaders.length > 0) {
                            roles = rolesHeaders[0].getValue().split(";");
                        }
                    } catch (Exception e) {
                        logger.error("Unable to compute roles");
                    }
                    statsLogger.info(OGCServiceMessageFormatter.format(authentication.getName(), sURL, org, roles));
                
                }
                	
            } catch (Exception e) {
                logger.error("Unable to log the request into the statistics logger", e);
            }

            if (localProxy) {
                //
                // Hack for geoserver
                // Should not be here. We must use a ProxyTarget class and
                // define
                // if Host header should be forwarded or not.
                //
                proxyingRequest.setHeader("Host", request.getHeader("Host"));

                if (logger.isDebugEnabled()) {
                    logger.debug("Host header set to: " + proxyingRequest.getFirstHeader("Host").getValue()
                            + " for proxy request.");
                }
            }

            proxiedResponse = executeHttpRequest(httpclient, proxyingRequest);
            StatusLine statusLine = proxiedResponse.getStatusLine();
            statusCode = statusLine.getStatusCode();
            String reasonPhrase = statusLine.getReasonPhrase();

            if (reasonPhrase != null && statusCode >= 400) {
                if (logger.isWarnEnabled()) {
                    logger.warn("Downstream server returned a status code which could be an error. "
                            + "Statuscode: " + statusCode + ", reason: " + reasonPhrase);
                }

                if (statusCode == 401) {
                    //
                    // Handle case of basic authentication.
                    //
                    Header authHeader = proxiedResponse.getFirstHeader("WWW-Authenticate");
                    finalResponse.setHeader("WWW-Authenticate", (authHeader == null) ? "Basic realm=\"Authentication required\"" : authHeader.getValue());
                }

                // 403 and 404 are handled by specific JSP files provided by the
                // security-proxy webapp
                if ((statusCode == 404) || (statusCode == 403)) {
                    // Hack for GN3.4: to protect against CSRF attacks, a token
                    // is provided by the xml.info service. Even if the return
                    // code is a 403, we are interested in getting the
                    // Set-Cookie value.
                    if (sURL.contains("/geonetwork/")) {
                        Header setCookie = extractHeaderSetCookie(proxiedResponse);
                        if (setCookie != null) {
                            finalResponse.addHeader(setCookie.getName(), setCookie.getValue());
                        }
                    }
                    finalResponse.sendError(statusCode);
                    return;
                }
            }

            headerManagement.copyResponseHeaders(request, request.getRequestURI(), proxiedResponse, finalResponse, this.targets);

            if (statusCode == 302 || statusCode == 301) {
                adjustLocation(request, proxiedResponse, finalResponse);
            }
            // get content type
            String contentType = null;
            if (proxiedResponse.getEntity() != null && proxiedResponse.getEntity().getContentType() != null) {
                contentType = proxiedResponse.getEntity().getContentType().getValue();
                logger.debug("content-type detected: " + contentType);
            }

            // content type has to be valid
            if (isCharsetRequiredForContentType(contentType)) {
                doHandleRequestCharsetRequired(request, finalResponse, proxiedResponse, contentType);
            } else {
                logger.debug("charset not required for contentType: " + contentType);
                doHandleRequest(request, finalResponse, proxiedResponse);
            }
        } catch (IOException e) {
            // connection problem with the host
            logger.error("Exception occured when trying to connect to the remote host: ", e);
            try {
                finalResponse.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            } catch (IOException e2) {
                // error occured while trying to return the
                // "service unavailable status"
                finalResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        } finally {
            try {
                httpclient.close();
            } catch (IOException e) {
                logger.error("Unable to close the httpclient", e);
            }
        }
    }

    /**
     * Extracts the set-cookie http header from the downstream response.
     *
     * @param proxiedResponse
     * @return the header if present, else null.
     */
    private Header extractHeaderSetCookie(HttpResponse proxiedResponse) {
        for (Header h : proxiedResponse.getAllHeaders()) {
            if (h.getName().equalsIgnoreCase(setCookieHeader)) {
                return h;
            }
        }
        return null;
    }

    @VisibleForTesting
    protected HttpResponse executeHttpRequest(HttpClient httpclient, HttpRequestBase proxyingRequest) throws IOException {
        return httpclient.execute(proxyingRequest);
    }

    private void copyLocationHeaders(HttpResponse proxiedResponse, HttpServletResponse finalResponse) {
        for (Header locationHeader : proxiedResponse.getHeaders("Location")) {
            finalResponse.addHeader(locationHeader.getName(), locationHeader.getValue());
        }
    }

    private void adjustLocation(HttpServletRequest request, HttpResponse proxiedResponse, HttpServletResponse finalResponse) {
        if (logger.isDebugEnabled()) {
            logger.debug("adjustLocation called for request: " + request.getRequestURI());
        }
        String target = findMatchingTarget(request);

        if (logger.isDebugEnabled()) {
            logger.debug("adjustLocation found target: " + target + " for request: " + request.getRequestURI());
        }

        if (target == null) {
            copyLocationHeaders(proxiedResponse, finalResponse);
            return;
        }

        String baseURL = targets.get(target);
        URI baseURI = null;

        try {
            baseURI = new URI(baseURL);
        } catch (URISyntaxException e) {
            copyLocationHeaders(proxiedResponse, finalResponse);
            return;
        }

        for (Header locationHeader : proxiedResponse.getHeaders("Location")) {
            if (logger.isDebugEnabled()) {
                logger.debug("adjustLocation process header: " + locationHeader.getValue());
            }
            try {
                URI locationURI = new URI(locationHeader.getValue());
                URI resolvedURI = baseURI.resolve(locationURI);

                if (logger.isDebugEnabled()) {
                    logger.debug("Test location header: " + resolvedURI.toString() + " against: " + baseURI.toString());
                }
                if (resolvedURI.toString().startsWith(baseURI.toString())) {
                    // proxiedResponse.removeHeader(locationHeader);
                    String newLocation = "/" + target + "/" + resolvedURI.toString().substring(baseURI.toString().length());
                    finalResponse.addHeader("Location", newLocation);
                    // Header newLocationHeader = new BasicHeader("Location",
                    // newLocation);
                    if (logger.isDebugEnabled()) {
                        logger.debug("adjustLocation from: " + locationHeader.getValue() + " to " + newLocation);
                    }
                    // proxiedResponse.addHeader(newLocationHeader);
                } else {
                    finalResponse.addHeader(locationHeader.getName(), locationHeader.getValue());
                }
            } catch (URISyntaxException e) {
                finalResponse.addHeader(locationHeader.getName(), locationHeader.getValue());
            }
        }

    }

    /**
     * Direct copy of response
     */
    private void doHandleRequest(HttpServletRequest request, HttpServletResponse finalResponse, HttpResponse proxiedResponse)
            throws IOException {

        org.apache.http.StatusLine statusLine = proxiedResponse.getStatusLine();

        int statusCode = statusLine.getStatusCode();
        finalResponse.setStatus(statusCode);
        HttpEntity entity = proxiedResponse.getEntity();
        if (entity != null) {
            // Send the Response
            OutputStream outputStream = finalResponse.getOutputStream();
            try {
                entity.writeTo(outputStream);
            } finally {
                outputStream.flush();
                outputStream.close();
            }
        }
    }

    private URI buildUri(URL url) throws URISyntaxException {
        // Let URI constructor encode Path part
        URI uri = new URI(url.getProtocol(),
                url.getUserInfo(),
                url.getHost(),
                url.getPort(),
                url.getPath(),
                null, // Don't use query part because URI constructor will try to double encode it
                // (query part is already encoded in sURL)
                url.getRef());

        // Reconstruct URL with encoded path from URI class and others parameters from URL class
        StringBuilder rawUrl = new StringBuilder(url.getProtocol() + "://" + url.getHost());

        if(url.getPort() != -1)
            rawUrl.append(":" + String.valueOf(url.getPort()));

        rawUrl.append(uri.getPath()); // Do not URL-encode, and take the request
        // as it originally comes instead of doing extra-encoding.

        if(url.getQuery() != null)
            rawUrl.append("?" + url.getQuery()); // Use already encoded query part

        return new URI(rawUrl.toString());
    }

    private HttpRequestBase makeRequest(HttpServletRequest request, String sURL) throws IOException {
        HttpRequestBase targetRequest;
        try {
            // Split URL
            URL url = new URL(sURL);
            URI uri = buildUri(url);
            HttpMethod meth = HttpMethod.resolve(request.getMethod());

            switch (meth) {
            case GET: {
                logger.debug("New request is: " + sURL + "\nRequest is GET");

                HttpGet get = new HttpGet(uri);
                targetRequest = get;
                break;
            }
            case POST: {
                logger.debug("New request is: " + sURL + "\nRequest is POST");

                HttpPost post = new HttpPost(uri);
                HttpEntity entity;
                request.setCharacterEncoding("UTF8");
                if (isFormContentType(request)) {
                    logger.debug("Post is a x-www-form-urlencoded POST");
                    List<NameValuePair> parameters = new ArrayList<NameValuePair>();
                    for (Enumeration e = request.getParameterNames(); e.hasMoreElements();) {
                        String name = (String) e.nextElement();
                        String[] v = request.getParameterValues(name);
                        for (String value : v) {
                            NameValuePair nv = new BasicNameValuePair(name, value);
                            parameters.add(nv);
                        }
                    }
                    String charset = request.getCharacterEncoding();
                    try {
                        Charset.forName(charset);
                    } catch (Throwable t) {
                        charset = null;
                    }
                    if (charset == null) {
                        charset = defaultCharset;
                    }
                    entity = new UrlEncodedFormEntity(parameters, charset);
                } else {
                    logger.debug("Post is a raw POST request");
                    int contentLength = request.getContentLength();
                    ServletInputStream inputStream = request.getInputStream();
                    entity = new InputStreamEntity(inputStream, contentLength);
                }
                post.setEntity(entity);
                targetRequest = post;
                break;
            }
            case TRACE: {
                logger.debug("New request is: " + sURL + "\nRequest is TRACE");

                HttpTrace post = new HttpTrace(uri);

                targetRequest = post;
                break;
            }
            case OPTIONS: {
                logger.debug("New request is: " + sURL + "\nRequest is OPTIONS");

                HttpOptions post = new HttpOptions(uri);

                targetRequest = post;
                break;
            }
            case HEAD: {
                logger.debug("New request is: " + sURL + "\nRequest is HEAD");

                HttpHead post = new HttpHead(uri);

                targetRequest = post;
                break;
            }
            case PUT: {
                logger.debug("New request is: " + sURL + "\nRequest is PUT");

                HttpPut put = new HttpPut(uri);

                put.setEntity(new InputStreamEntity(request.getInputStream(), request.getContentLength()));

                targetRequest = put;
                break;
            }
            case DELETE: {
                logger.debug("New request is: " + sURL + "\nRequest is DELETE");

                HttpDelete delete = new HttpDelete(uri);

                targetRequest = delete;
                break;
            }
            default: {
                String msg = meth + " not yet supported";
                logger.error(msg);
                throw new IllegalArgumentException(msg);
            }

            }
        } catch (URISyntaxException e) {
            logger.error("ERROR creating URI from " + sURL, e);
            throw new IOException(e);
        }

        return targetRequest;
    }

    /**
     * Returns if the request is a POST x-www-form-urlencoded or not.
     *
     * @param request
     * @return true if this is the case, else false.
     *
     */
    private boolean isFormContentType(HttpServletRequest request) {
        if (request.getContentType() == null) {
            return false;
        }
        String contentType = request.getContentType().split(";")[0].trim();

        return "application/x-www-form-urlencoded".equalsIgnoreCase(contentType);
    }

    /**
     * For certain requests (OGC Web services mainly), the charset is absolutely
     * required. So for certain content types (xml-based normally) this method
     * is called to detect the charset of the data. This method is a slow way of
     * transferring data, so data of any significant size should not enter this
     * method.
     */
    private void doHandleRequestCharsetRequired(HttpServletRequest orignalRequest, HttpServletResponse finalResponse,
            HttpResponse proxiedResponse, String contentType) {

        InputStream streamFromServer = null;
        OutputStream streamToClient = null;

        try {

            /*
             * Here comes the tricky part because some host send files without
             * the charset in the header, therefore we do not know how they are
             * text encoded. It can result in serious issues on IE browsers when
             * parsing those files. There is a workaround which consists to read
             * the encoding within the file. It is made possible because this
             * proxy mainly forwards xml files. They all have the encoding
             * attribute in the first xml node.
             *
             * This is implemented as follows:
             *
             * A. The content type provides a charset: Nothing special, just
             * send back the stream to the client B. There is no charset
             * provided: The encoding has to be extracted from the file. The
             * file is read in ASCII, which is common to many charsets, like
             * that the encoding located in the first not can be retrieved. Once
             * the charset is found, the content-type header is overridden and
             * the charset is appended.
             *
             * /!\ Special case: whenever data are compressed in gzip/deflate
             * the stream has to be uncompressed and re-compressed
             */

            boolean isCharsetKnown = proxiedResponse.getEntity().getContentType().getValue().toLowerCase().contains("charset");
            // String contentEncoding =
            // getContentEncoding(proxiedResponse.getAllHeaders());
            String contentEncoding = getContentEncoding(proxiedResponse.getHeaders("Content-Encoding"));

            if (logger.isDebugEnabled()) {

                String cskString = "\tisCharSetKnown=" + isCharsetKnown;
                String cEString = "\tcontentEncoding=" + contentEncoding;
                logger.debug("Charset is required so verifying that it has been added to the headers\n" + cskString + "\n" + cEString);
            }

            if (contentEncoding == null || isCharsetKnown) {
                // A simple stream can do the job for data that is not in
                // content encoded
                // but also for data content encoded with a known charset
                streamFromServer = proxiedResponse.getEntity().getContent();
                streamToClient = finalResponse.getOutputStream();
            } else if (!isCharsetKnown && ("gzip".equalsIgnoreCase(contentEncoding) || "x-gzip".equalsIgnoreCase(contentEncoding))) {
                // the charset is unknown and the data are compressed in gzip
                // we add the gzip wrapper to be able to read/write the stream
                // content
                streamFromServer = new GZIPInputStream(proxiedResponse.getEntity().getContent());
                streamToClient = new GZIPOutputStream(finalResponse.getOutputStream());
            } else if ("deflate".equalsIgnoreCase(contentEncoding) && !isCharsetKnown) {
                // same but with deflate
                streamFromServer = new DeflaterInputStream(proxiedResponse.getEntity().getContent());
                streamToClient = new DeflaterOutputStream(finalResponse.getOutputStream());
            } else {
                doHandleRequest(orignalRequest, finalResponse, proxiedResponse);
                return;
            }

            byte[] buf = new byte[1024]; // read maximum 1024 bytes
            int len; // number of bytes read from the stream
            boolean first = true; // helps to find the encoding once and only
                                  // once
            String s = ""; // piece of file that should contain the encoding
            while ((len = streamFromServer.read(buf)) > 0) {

                if (first && !isCharsetKnown) {
                    // charset is unknown try to find it in the file content
                    for (int i = 0; i < len; i++) {
                        s += (char) buf[i]; // get the beginning of the file as
                                            // ASCII
                    }
                    // s has to be long enough to contain the encoding
                    if (s.length() > 200) {

                        if (logger.isTraceEnabled()) {
                            logger.trace("attempting to read charset from: " + s);
                        }
                        String charset = getCharset(s); // extract charset

                        if (charset == null) {
                            if (logger.isTraceEnabled()) {
                                logger.trace("unable to find charset from raw ASCII data.  Trying to unzip it");
                            }

                            // the charset cannot be found, IE users must be
                            // warned
                            // that the request cannot be fulfilled, nothing
                            // good would happen otherwise
                        }
                        if (charset == null) {
                            String guessedCharset = null;
                            if (logger.isDebugEnabled()) {
                                logger.debug("unable to find charset so using the first one from the accept-charset request header");
                            }
                            String calculateDefaultCharset = calculateDefaultCharset(orignalRequest);
                            if (calculateDefaultCharset != null) {
                                guessedCharset = calculateDefaultCharset;
                                if (logger.isDebugEnabled()) {
                                    logger.debug("hopefully the server responded with this charset: " + calculateDefaultCharset);
                                }
                            } else {
                                guessedCharset = defaultCharset;
                                if (logger.isDebugEnabled()) {
                                    logger.debug("unable to find charset, so using default:" + defaultCharset);
                                }
                            }
                            String adjustedContentType = proxiedResponse.getEntity().getContentType().getValue() + ";charset=" + guessedCharset;
                            finalResponse.setHeader("Content-Type", adjustedContentType);
                            first = false; // we found the encoding, don't try
                                           // to do it again
                            finalResponse.setCharacterEncoding(guessedCharset);

                        } else {
                            if (logger.isDebugEnabled()) {
                                logger.debug("found charset: " + charset);
                            }
                            String adjustedContentType = proxiedResponse.getEntity().getContentType().getValue() + ";charset=" + charset;
                            finalResponse.setHeader("Content-Type", adjustedContentType);
                            first = false; // we found the encoding, don't try
                                           // to do it again
                            finalResponse.setCharacterEncoding(charset);
                        }
                    }
                }

                // for everyone, the stream is just forwarded to the client
                streamToClient.write(buf, 0, len);
            }

        } catch (IOException e) {
            // connection problem with the host
            e.printStackTrace();
        } finally {
            IOException exc = close(streamFromServer);
            exc = close(streamToClient, exc);
            if (exc != null) {
                logger.error("Error closing streams", exc);
            }
        }
    }

    private String calculateDefaultCharset(HttpServletRequest originalRequest) {
        String acceptCharset = originalRequest.getHeader("accept-charset");

        String calculatedCharset = null;

        if (acceptCharset != null) {
            calculatedCharset = acceptCharset.split(",")[0];
        }

        return calculatedCharset;
    }

    private IOException close(Closeable stream, IOException... previousExceptions) {
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException e) {
            if (previousExceptions.length > 0) {
                return previousExceptions[0];
            }
            return e;
        }
        if (previousExceptions.length > 0) {
            return previousExceptions[0];
        }
        return null;
    }

    /**
     * Extract the encoding from a string which is the header node of an xml
     * file
     *
     * @param header
     *            String that should contain the encoding attribute and its
     *            value
     * @return the charset. null if not found
     */
    private String getCharset(String header) {
        Pattern pattern = null;
        String charset = null;
        try {
            // use a regexp but we could also use string functions such as
            // indexOf...
            pattern = Pattern.compile("encoding=(['\"])([A-Za-z]([A-Za-z0-9._]|-)*)");
        } catch (Exception e) {
            throw new RuntimeException("expression syntax invalid");
        }

        Matcher matcher = pattern.matcher(header);
        if (matcher.find()) {
            String encoding = matcher.group();
            charset = encoding.split("['\"]")[1];
        }

        return charset;
    }

    /**
     * Gets the encoding of the content sent by the remote host: extracts the
     * content-encoding header
     *
     * @param headers
     *            headers of the HttpURLConnection
     * @return null if not exists otherwise name of the encoding (gzip,
     *         deflate...)
     */
    private String getContentEncoding(Header[] headers) {
        if (headers == null || headers.length == 0) {
            if (logger.isDebugEnabled()) {
                logger.debug("No content-encoding header for this request.");
            }
            return null;
        }
        for (Header header : headers) {
            // Header header = headers[i];
            String headerName = header.getName();
            if (logger.isDebugEnabled()) {
                logger.debug("Check content-encoding against header: " + headerName + " : " + header.getValue());
            }
            if (headerName != null && "Content-Encoding".equalsIgnoreCase(headerName)) {
                return header.getValue();
            }
        }

        return null;
    }

    /**
     * Check if the content type is accepted by the proxy
     *
     * @param contentType
     * @return true: valid; false: not valid
     */
    protected boolean isCharsetRequiredForContentType(final String contentType) {
        if (contentType == null) {
            return false;
        }
        // focus only on type, not on the text encoding
        String type = contentType.split(";")[0];
        for (String validTypeContent : requireCharsetContentTypes) {
            logger.debug(contentType + " vs " + validTypeContent + "=" + (validTypeContent.equalsIgnoreCase(type)));
            if (validTypeContent.equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }

    private String[] filter(String[] one) {
        ArrayList<String> result = new ArrayList<String>();

        for (String string : one) {
            if (string.length() > 0) {
                result.add(string);
            }
        }
        return result.toArray(new String[result.size()]);
    }

    /**
     * Check to see if the call is recursive based on forwardRequestURI
     * startsWith contextPath
     */
    private boolean isRecursiveCallToProxy(String forwardRequestURI, String contextPath) {
        String[] one = forwardRequestURI.split("/");
        String[] two = contextPath.split("/");

        one = filter(one);
        two = filter(two);

        if (one.length < two.length) {
            return false;
        }

        boolean match = true;
        for (int i = 0; i < two.length && i < one.length; i++) {
            String s2 = two[i];
            String s1 = one[i];

            match &= s2.equalsIgnoreCase(s1);
        }
        return match;
    }

    public void setDefaultTarget(String defaultTarget) {
        this.defaultTarget = defaultTarget;
    }

    public void setTargets(Map<String, String> targets) {
        this.targets = targets;
    }

    public void setContextpath(String contextpath) {
        // this.contextpath = contextpath;
    }

    public void setHeaderManagement(HeadersManagementStrategy headerManagement) {
        this.headerManagement = headerManagement;
    }

    public void setRequireCharsetContentTypes(List<String> requireCharsetContentTypes) {
        this.requireCharsetContentTypes = requireCharsetContentTypes;
    }

    public void setStrategyForFilteringRequests(FilterRequestsStrategy strategyForFilteringRequests) {
        this.strategyForFilteringRequests = strategyForFilteringRequests;
    }

    public void setDefaultCharset(String defaultCharset) {
        try {
            Charset.forName(defaultCharset);
        } catch (Throwable t) {
            throw new IllegalArgumentException(defaultCharset + " is not supported by current JVM");
        }
        this.defaultCharset = defaultCharset;
    }

    public void setRedirectStrategy(RedirectStrategy redirectStrategy) {
        this.redirectStrategy = redirectStrategy;
    }

    public void setProxyPermissionsFile(String proxyPermissionsFile) {
        this.proxyPermissionsFile = proxyPermissionsFile;
    }

    public void setProxyPermissions(Permissions proxyPermissions) throws UnknownHostException {
        this.proxyPermissions = proxyPermissions;
    }

    public Permissions getProxyPermissions() {
        return proxyPermissions;
    }

}
