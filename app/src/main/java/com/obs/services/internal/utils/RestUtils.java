/**
 * Copyright 2019 Huawei Technologies Co.,Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.obs.services.internal.utils;

import com.obs.log.ILogger;
import com.obs.log.LoggerBuilder;
import com.obs.services.exception.ObsException;
import com.obs.services.internal.Constants;
import com.obs.services.internal.Constants.CommonHeaders;
import com.obs.services.internal.ObsConstraint;
import com.obs.services.internal.ObsProperties;
import com.obs.services.internal.ServiceException;
import com.obs.services.internal.ext.ExtObsConstraint;
import com.obs.services.model.HttpProtocolTypeEnum;
import okhttp3.Authenticator;
import okhttp3.ConnectionPool;
import okhttp3.Credentials;
import okhttp3.Dispatcher;
import okhttp3.Dns;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

import javax.net.SocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy.Type;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RestUtils {

    private static final ILogger log = LoggerBuilder.getLogger(RestUtils.class);
    private static final Set<Character> SPECIAL_CHAR = new HashSet<>();

    static {
        SPECIAL_CHAR.add('_');
        SPECIAL_CHAR.add('-');
        SPECIAL_CHAR.add('~');
        SPECIAL_CHAR.add('.');
    }

    //CHECKSTYLE:OFF
    private static Pattern chinesePattern = Pattern.compile("[\u4e00-\u9fa5]");

    private static final X509TrustManager TRUST_ALL_MANAGER = new X509TrustManager() {

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[] {};
        }

        @Override
        public void checkServerTrusted(X509Certificate[] arg0, String arg1) throws CertificateException {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] arg0, String arg1) throws CertificateException {
        }
    };

    public static String uriEncode(CharSequence input, boolean chineseOnly) throws ServiceException {

        StringBuilder result = new StringBuilder();
        try {
            tryEncode(input, chineseOnly, result);
        } catch (UnsupportedEncodingException e) {
            throw new ServiceException("Unable to encode input: " + input);
        }
        return result.toString();
    }

    private static void tryEncode(CharSequence input, boolean chineseOnly, StringBuilder result)
            throws UnsupportedEncodingException {
        if (chineseOnly) {
            for (int i = 0; i < input.length(); i++) {
                char ch = input.charAt(i);
                String s = Character.toString(ch);
                Matcher m = chinesePattern.matcher(s);
                if (m != null && m.find()) {
                    result.append(URLEncoder.encode(s, Constants.DEFAULT_ENCODING));
                } else {
                    result.append(ch);
                }
            }
        } else {
            for (int i = 0; i < input.length(); i++) {
                char ch = input.charAt(i);
                boolean isAlpha = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
                boolean isNumber = (ch >= '0' && ch <= '9');
                if (isAlpha || isNumber || SPECIAL_CHAR.contains(ch)) {
                    result.append(ch);
                } else if (ch == '/') {
                    result.append("%2F");
                } else {
                    result.append(URLEncoder.encode(Character.toString(ch), Constants.DEFAULT_ENCODING));
                }
            }
        }
    }

    public static String encodeUrlString(String path) throws ServiceException {
        try {
            return URLEncoder.encode(path, Constants.DEFAULT_ENCODING).replaceAll("\\+", "%20") // Web
                                                                                                // browsers
                                                                                                // do
                                                                                                // not
                                                                                                // always
                                                                                                // handle
                                                                                                // '+'
                                                                                                // characters
                                                                                                // well,
                                                                                                // use
                                                                                                // the
                                                                                                // well-supported
                                                                                                // '%20'
                                                                                                // instead.
                    .replaceAll("%7E", "~").replaceAll("\\*", "%2A");
        } catch (UnsupportedEncodingException uee) {
            throw new ServiceException("Unable to encode path: " + path, uee);
        }
    }

    public static String encodeUrlPath(String path, String delimiter) throws ServiceException {
        String allEncoded = encodeUrlString(path);
        return allEncoded.replaceAll(encodeUrlString(delimiter),delimiter);
    }

    private static SSLContext createSSLContext(KeyManager[] km, TrustManager[] tm, String provider,
                                               SecureRandom secureRandom) throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLSv1.2", provider);
        sslContext.init(km, tm, secureRandom);
        return sslContext;
    }

    private static SSLContext createSSLContext(KeyManager[] km, TrustManager[] tm,
                                               SecureRandom secureRandom) throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(km, tm, secureRandom);
        return sslContext;
    }

    private static class WrapperedSocketFactory extends SocketFactory {

        private SocketFactory delegate;
        private int socketReadBufferSize;
        private int socketWriteBufferSize;

        WrapperedSocketFactory(SocketFactory delegate,
                int socketReadBufferSize, int socketWriteBufferSize) {
            this.delegate = delegate;
            this.socketReadBufferSize = socketReadBufferSize;
            this.socketWriteBufferSize = socketWriteBufferSize;
        }

        private Socket doWrap(Socket s) throws SocketException {
            if (s != null) {
                if (socketReadBufferSize > 0) {
                    s.setReceiveBufferSize(socketReadBufferSize);
                }
                if (socketWriteBufferSize > 0) {
                    s.setSendBufferSize(socketWriteBufferSize);
                }
                s.setTcpNoDelay(true);
            }
            return s;
        }

        @Override
        public Socket createSocket() throws IOException, UnknownHostException {
            return this.doWrap(this.delegate.createSocket());
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
            return this.doWrap(this.delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return this.doWrap(this.delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                throws IOException, UnknownHostException {
            return this.doWrap(this.delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return this.doWrap(this.delegate.createSocket(address, port, localAddress, localPort));
        }

    }

    private static class WrapperedSSLSocketFactory extends SSLSocketFactory {

        private SSLSocketFactory delegate;
        private int socketReadBufferSize;
        private int socketWriteBufferSize;

        WrapperedSSLSocketFactory(SSLSocketFactory delegate,
                int socketReadBufferSize, int socketWriteBufferSize) {
            this.delegate = delegate;
            this.socketReadBufferSize = socketReadBufferSize;
            this.socketWriteBufferSize = socketWriteBufferSize;
        }

        private Socket doWrap(Socket s) throws SocketException {
            if (s != null) {
                if (socketReadBufferSize > 0) {
                    s.setReceiveBufferSize(socketReadBufferSize);
                }
                if (socketWriteBufferSize > 0) {
                    s.setSendBufferSize(socketWriteBufferSize);
                }
                s.setTcpNoDelay(true);
            }
            return s;
        }

        @Override
        public Socket createSocket() throws IOException, UnknownHostException {
            return this.doWrap(this.delegate.createSocket());
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return doWrap(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
            return doWrap(this.delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return doWrap(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                throws IOException, UnknownHostException {
            return doWrap(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return doWrap(delegate.createSocket(address, port, localAddress, localPort));
        }

    }

    public static OkHttpClient.Builder initHttpClientBuilder(ObsProperties obsProperties,
            KeyManagerFactory keyManagerFactory, TrustManagerFactory trustManagerFactory,
            Dispatcher httpDispatcher, Dns customizedDnsImpl, EventListener.Factory eventListenerFactory,
            HostnameVerifier userHostnameVerifier, SecureRandom secureRandom, SSLContext customSSLContext) {

        List<Protocol> protocols = new ArrayList<Protocol>(2);
        protocols.add(Protocol.HTTP_1_1);

        if (HttpProtocolTypeEnum.getValueFromCode(obsProperties.getStringProperty(ObsConstraint.HTTP_PROTOCOL,
                HttpProtocolTypeEnum.HTTP1_1.getCode())) == HttpProtocolTypeEnum.HTTP2_0) {
            protocols.add(Protocol.HTTP_2);
        }

        OkHttpClient.Builder builder = new OkHttpClient.Builder();

        initHttpDispatcher(obsProperties, httpDispatcher, builder);

        ConnectionPool pool = new ConnectionPool(
                obsProperties.getIntProperty(ObsConstraint.HTTP_MAX_IDLE_CONNECTIONS,
                        ObsConstraint.DEFAULT_MAX_IDLE_CONNECTIONS),
                obsProperties.getIntProperty(ObsConstraint.HTTP_IDLE_CONNECTION_TIME,
                        ObsConstraint.DEFAULT_IDLE_CONNECTION_TIME),
                TimeUnit.MILLISECONDS);

        Dns dns = customizedDnsImpl == null ? new DefaultObsDns() : customizedDnsImpl;
        HostnameVerifier hostnameVerifier = (s, sslSession) -> {
            if(obsProperties.getBoolProperty(
                    ObsConstraint.HTTP_STRICT_HOSTNAME_VERIFICATION, false)) {
                if(userHostnameVerifier == null) {
                    return HttpsURLConnection.getDefaultHostnameVerifier().verify
                            (obsProperties.getStringProperty(ObsConstraint.END_POINT, ""), sslSession);
                } else {
                    return userHostnameVerifier.verify(s, sslSession);
                }
            } else {
                // no hostnameVerify
                return true;
            }
        };

        builder.protocols(protocols).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(
                        obsProperties.getBoolProperty(ExtObsConstraint.IS_RETRY_ON_CONNECTION_FAILURE_IN_OKHTTP, false))
                .cache(null)
                .connectTimeout(obsProperties.getIntProperty(ObsConstraint.HTTP_CONNECT_TIMEOUT,
                        ObsConstraint.HTTP_CONNECT_TIMEOUT_VALUE), TimeUnit.MILLISECONDS)
                .callTimeout(obsProperties.getIntProperty(ObsConstraint.HTTP_CALL_TIMEOUT,
                        ObsConstraint.HTTP_CALL_TIMEOUT_VALUE), TimeUnit.MILLISECONDS)
                .writeTimeout(obsProperties.getIntProperty(ObsConstraint.HTTP_SOCKET_TIMEOUT,
                        ObsConstraint.HTTP_SOCKET_TIMEOUT_VALUE), TimeUnit.MILLISECONDS)
                .readTimeout(obsProperties.getIntProperty(ObsConstraint.HTTP_SOCKET_TIMEOUT,
                        ObsConstraint.HTTP_SOCKET_TIMEOUT_VALUE), TimeUnit.MILLISECONDS)
                .connectionPool(pool)
                .hostnameVerifier(hostnameVerifier)
                .dns(dns);

        if (eventListenerFactory != null) {
            builder.eventListenerFactory(eventListenerFactory);
        }

        int socketReadBufferSize = obsProperties.getIntProperty(ObsConstraint.SOCKET_READ_BUFFER_SIZE, -1);
        int socketWriteBufferSize = obsProperties.getIntProperty(ObsConstraint.SOCKET_WRITE_BUFFER_SIZE, -1);

        builder.socketFactory(new WrapperedSocketFactory(SocketFactory.getDefault(), socketReadBufferSize, socketWriteBufferSize));

        try {
            KeyManager[] km = null;
            X509TrustManager trustManager;
            TrustManager[] tm;

            if (obsProperties.getBoolProperty(ObsConstraint.VALIDATE_CERTIFICATE, false)) {
                km = keyManagerFactory == null ? null : keyManagerFactory.getKeyManagers();
                if (trustManagerFactory == null || trustManagerFactory.getTrustManagers().length < 1) {
                    trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                    trustManagerFactory.init((KeyStore) null);
                }
                tm = trustManagerFactory.getTrustManagers();
                trustManager = (X509TrustManager) tm[0];
            } else {
                trustManager = TRUST_ALL_MANAGER;
                tm = new TrustManager[] {trustManager};
            }
            String provider = obsProperties.getStringProperty(ObsConstraint.SSL_PROVIDER, "");
            SSLContext sslContext = null;
            if (ServiceUtils.isValid(provider)) {
                try {
                    sslContext = createSSLContext(km, tm, provider, secureRandom);
                } catch (Exception e) {
                    if (log.isErrorEnabled()) {
                        log.error("Exception happened in create ssl context with provider:" + provider, e);
                    }
                }
            }
            if (sslContext == null) {
                try {
                    sslContext = createSSLContext(km, tm, secureRandom);
                } catch (Exception e) {
                    log.error("Exception happened in create ssl context", e);
                    sslContext = customSSLContext;
                    log.info("Failed to create ssl context, use customSSLContext now.");
                }
            }
            if (sslContext != null) {
                SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
                builder.sslSocketFactory(new WrapperedSSLSocketFactory(sslSocketFactory,
                        socketReadBufferSize,
                        socketWriteBufferSize),
                    trustManager);
            } else {
                log.error("Failed to create ssl context, customSSLContext is null! sslSocketFactory not set in "
                    + "OkHttpClient.Builder!");
            }
        } catch (Exception e) {
            if (log.isErrorEnabled()) {
                log.error("Exception happened in HttpClient.configSSL,and e = " + e);
            }
        }

        return builder;
    }

    private static void initHttpDispatcher(ObsProperties obsProperties, Dispatcher httpDispatcher,
            OkHttpClient.Builder builder) {
        if (httpDispatcher == null) {
            int maxConnections = obsProperties.getIntProperty(ObsConstraint.HTTP_MAX_CONNECT,
                    ObsConstraint.HTTP_MAX_CONNECT_VALUE);
            httpDispatcher = new Dispatcher();
            httpDispatcher.setMaxRequests(maxConnections);
            httpDispatcher.setMaxRequestsPerHost(maxConnections);
            builder.dispatcher(httpDispatcher);
        } else {
            try {
                Method m = builder.getClass().getMethod("dispatcher", httpDispatcher.getClass());
                m.invoke(builder, httpDispatcher);
            } catch (Exception e) {
                if (log.isWarnEnabled()) {
                    log.warn("invoke " + httpDispatcher.getClass() + ".dispatcher() failed.", e);
                }
                try {
                    Class<?> c = Class.forName("okhttp3.AbsDispatcher");
                    Method m = builder.getClass().getMethod("dispatcher", c);
                    m.invoke(builder, httpDispatcher);
                } catch (Exception ex) {
                    throw new ObsException("invoke okhttp3.AbsDispatcher.dispatcher failed", ex);
                }
            }
        }
    }

    public static void initHttpProxy(OkHttpClient.Builder builder, String proxyHostAddress, int proxyPort,
            final String proxyUser, final String proxyPassword) {
        if (proxyHostAddress != null && proxyPort != -1) {
            if (log.isInfoEnabled()) {
                log.info("Using Proxy: " + proxyHostAddress + ":" + proxyPort);
            }
            builder.proxy(new java.net.Proxy(Type.HTTP, new InetSocketAddress(proxyHostAddress, proxyPort)));

            if (proxyUser != null && !proxyUser.trim().equals("")) {
                Authenticator proxyAuthenticator = new Authenticator() {
                    @Override
                    public Request authenticate(Route route, Response response) throws IOException {
                        String credential = Credentials.basic(proxyUser, proxyPassword);
                        return response.request().newBuilder().header(CommonHeaders.PROXY_AUTHORIZATION, credential)
                                .build();
                    }
                };
                builder.proxyAuthenticator(proxyAuthenticator);
            }
        }
    }

    public static String readBodyFromResponse(Response response) {
        String body;
        try {
            body = response.body().string();
        } catch (IOException e) {
            throw new ServiceException(e);
        }
        return body;
    }
    public static class DefaultObsDns implements Dns {
        public DefaultObsDns() {
            log.info("use Default Dns");
        }

        /**
         * @param hostname
         * @return
         * @throws UnknownHostException
         */
        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            List<InetAddress> adds = Dns.SYSTEM.lookup(hostname);
            log.info("internet host address:" + adds);
            return adds;
        }
    }
}
