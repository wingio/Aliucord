/*
 * This file is part of Aliucord, an Android Discord client mod.
 * Copyright (c) 2021 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */

package com.aliucord;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.aliucord.utils.*;

import com.discord.stores.StoreStream;
import com.discord.utilities.analytics.AnalyticSuperProperties;
import com.discord.utilities.rest.RestAPI;

import java.io.*;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.net.*;
import java.security.*;
import java.util.*;

/** Http Utilities */
@SuppressWarnings({"unused", "UnusedReturnValue"})
public class Http {
    public static class HttpException extends IOException {
        /** The url of this request */
        public final URL url;
        /** The HTTP method of this request */
        public final String method;
        /** The status code of the response */
        public final int statusCode;
        /** The status message of the response */
        public final String statusMessage;
        /** The raw Request object */
        public final Request req;
        /** The raw Response object */
        public final Response res;

        /** Creates a new HttpException for the specified Request and Response */
        public HttpException(Request req, Response res) {
            super();
            this.req = req;
            this.res = res;
            this.statusCode = res.statusCode;
            this.statusMessage = res.statusMessage;
            this.method = req.conn.getRequestMethod();
            this.url = req.conn.getURL();
        }

        private String message;
        @Override
        @NonNull
        public String getMessage() {
            if (message == null) {
                var sb = new StringBuilder()
                        .append(res.statusCode)
                        .append(": ")
                        .append(res.statusMessage)
                        .append(" (")
                        .append(req.conn.getURL())
                        .append(')');

                try (var eis = req.conn.getErrorStream()) {
                    var s = IOUtils.readAsText(eis);
                    sb.append('\n').append(s);
                } catch (Throwable ignored) {
                }

                message = sb.toString();
            }
            return message;
        }
    }

    /** QueryString Builder */
    public static class QueryBuilder {
        private final StringBuilder sb;

        public QueryBuilder(String baseUrl) {
            sb = new StringBuilder(baseUrl + "?");
        }

        /**
         * Append query parameter. Will automatically be encoded for you
         * @param key The parameter key
         * @param value The parameter value
         * @return self
         */
        public QueryBuilder append(String key, String value) {
            try {
                key = URLEncoder.encode(key, "UTF-8");
                value = URLEncoder.encode(value, "UTF-8");
                sb.append(key).append('=').append(value).append('&');
            } catch (UnsupportedEncodingException ignored) {} // This should never happen
            return this;
        }

        /**
         * Build the finished Url
         */
        @NonNull
        public String toString() {
            String str = sb.toString();
            return str.substring(0, str.length() -1); // Remove last & or ? if no query specified
        }
    }

    /** Request Builder */
    public static class Request implements Closeable {
        /** The connection of this Request */
        public final HttpURLConnection conn;

        /**
         * Builds a GET request with the specified QueryBuilder
         * @param builder QueryBuilder
         * @throws IOException If an I/O exception occurs
         */
        public Request(QueryBuilder builder) throws IOException {
            this(builder.toString(), "GET");
        }

        /**
         * Builds a GET request with the specified url
         * @param url Url
         * @throws IOException If an I/O exception occurs
         */
        public Request(String url) throws IOException {
            this(url, "GET");
        }

        /**
         * Builds a request with the specified url and method
         * @param url Url
         * @param method <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods">HTTP method</a>
         * @throws IOException If an I/O exception occurs
         */
        public Request(String url, String method) throws IOException {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method.toUpperCase());
            conn.addRequestProperty("User-Agent", "Aliucord (https://github.com/Aliucord/Aliucord)");
        }

        /**
         * Add a header
         * @param key the name
         * @param value the value
         * @return self
         */
        public Request setHeader(String key, String value) {
            conn.setRequestProperty(key, value);
            return this;
        }

        /**
         * Sets the request connection and read timeout
         * @param timeout the timeout, in milliseconds
         * @return self
         */
        public Request setRequestTimeout(int timeout) {
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            return this;
        }

        /**
         * Sets whether redirects should be followed
         * @param follow Whether redirects should be followed
         * @return self
         */
        public Request setFollowRedirects(boolean follow) {
            conn.setInstanceFollowRedirects(follow);
            return this;
        }

        /**
         * Execute the request
         * @return A response object
         */
        public Response execute() throws IOException {
            return new Response(this);
        }

        /**
         * Execute the request with the specified body. May not be used in GET requests.
         * @param body The request body
         * @return Response
         */
        public Response executeWithBody(String body) throws IOException {
            if (conn.getRequestMethod().equals("GET")) throw new IOException("Body may not be specified in GET requests");
            byte[] bytes = body.getBytes();
            setHeader("Content-Length", Integer.toString(bytes.length));
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes, 0, bytes.length);
                out.flush();
            }
            return execute();
        }

        /**
         * Execute the request with the specified object as json. May not be used in GET requests.
         * @param body The request body
         * @return Response
         */
        public Response executeWithJson(Object body) throws IOException {
            return setHeader("Content-Type", "application/json").executeWithBody(GsonUtils.toJson(body));
        }

        /**
         * Execute the request with the specified object as
         * <a href="https://url.spec.whatwg.org/#application/x-www-form-urlencoded">url encoded form data</a>.
         * May not be used in GET requests.
         * @param params the form data
         * @return Response
         * @throws IOException if an I/O exception occurred
         */
        public Response executeWithUrlEncodedForm(Map<String, Object> params) throws IOException {
            QueryBuilder qb = new QueryBuilder("");
            for (Map.Entry<String, Object> entry : params.entrySet())
                qb.append(entry.getKey(), Objects.toString(entry.getValue()));

            return setHeader("Content-Type", "application/x-www-form-urlencoded").executeWithBody(qb.toString().substring(1));
        }

        /** Closes this request */
        @Override
        public void close() {
            conn.disconnect();
        }
    }

    /** Response obtained by calling Request.execute() */
    public static class Response implements Closeable {
        private final Request req;
        /** The <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status">status code</a> of this response */
        public final int statusCode;
        /** The <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status">status message</a> of this response */
        public final String statusMessage;

        /**
         * Construct a Response
         * @param req The http request to execute
         * @throws IOException If an error occurred connecting to the server
         */
        public Response(Request req) throws IOException {
            this.req = req;
            statusCode = req.conn.getResponseCode();
            statusMessage = req.conn.getResponseMessage();
        }

        /** Whether the request was successful (status code 2xx) */
        public boolean ok() {
            return statusCode >= 200 && statusCode < 300;
        }

        /** Throws an HttpException if this request was unsuccessful */
        public void assertOk() throws HttpException {
            if (!ok()) throw new HttpException(req, this);
        }

        /** Get the response body as String */
        public String text() throws IOException {
            try (var is = stream()) {
                return IOUtils.readAsText(is);
            }
        }

        /** Get the response body as <code>byte[]</code> */
        public byte[] getBytes() throws IOException {
            try (var is = stream()) {
                return IOUtils.readBytes(is);
            }
        }

        /**
         * Deserializes json response
         * @param type Type to deserialize into
         * @return Response Object
         */
        public <T> T json(Type type) throws IOException {
            return GsonUtils.fromJson(text(), type);
        }

        /**
         * Deserializes json response
         * @param type Class to deserialize into
         * @return Response Object
         */
        public <T> T json(Class<T> type) throws IOException {
            return GsonUtils.fromJson(text(), type);
        }

        /**
         * Get the raw response stream of this connection
         * @return InputStream
         */
        public InputStream stream() throws IOException {
            assertOk();
            return req.conn.getInputStream();
        }

        /**
         * Pipe response into OutputStream. Remember to close the OutputStream
         * @param os The OutputStream to pipe into
         */
        public void pipe(OutputStream os) throws IOException {
            try (InputStream is = stream()) {
                IOUtils.pipe(is, os);
            }
        }

        /**
         * Saves the received data to the specified {@link File}
         * @param file The file to save the data to
         * @throws IOException If an I/O error occurred: No such file, file is directory, etc
         */
        public void saveToFile(@NonNull File file) throws IOException {
            saveToFile(file, null);
        }

        /**
         * Saves the received data to the specified {@link File}
         * and verifies its integrity using the specified sha1sum
         * @param file The file to save the data to
         * @param sha1sum checksum to check the file's integrity. May be null to skip integrity check
         * @throws IOException If an I/O error occurred: No such file, file is directory, integrity check failed, etc
         */
        public void saveToFile(@NonNull File file, @Nullable String sha1sum) throws IOException {
            if (file.exists()) {
                if (!file.canWrite())
                    throw new IOException("Cannot write to file: " + file.getAbsolutePath());
                if (file.isDirectory())
                    throw new IOException("Path already exists and is directory: " + file.getAbsolutePath());
            }

            var parent = file.getParentFile();
            if (parent == null)
                throw new IOException("Only absolute paths are supported.");

            var tempFile = File.createTempFile("download", null, parent);
            try {
                boolean shouldVerify = sha1sum != null;
                var md = shouldVerify ? MessageDigest.getInstance("SHA-1") : null;
                try (
                        var is = shouldVerify ? new DigestInputStream(stream(), md) : stream();
                        var os = new FileOutputStream(tempFile)
                ) {
                    IOUtils.pipe(is, os);

                    if (shouldVerify) {
                        var hash = String.format("%040x", new BigInteger(1, md.digest()));
                        if (!hash.equalsIgnoreCase(sha1sum))
                            throw new IOException(String.format("Integrity check failed. Expected %s, received %s.", sha1sum, hash));
                    }

                    if (!tempFile.renameTo(file))
                        throw new IOException("Failed to rename temp file.");
                } catch (IOException ex) {
                    if (tempFile.exists() && !tempFile.delete())
                        Main.logger.warn("[HTTP#saveToFile] Failed to clean up temp file " + tempFile.getAbsolutePath());
                    throw ex;
                }
            } catch (NoSuchAlgorithmException ex) {
                throw new RuntimeException("Failed to retrieve SHA-1 MessageDigest instance", ex);
            }
        }

        /**
         * Closes the {@link Request} associated with this {@link Response}
         */
        @Override
        public void close() {
            req.close();
        }
    }

    /** Request Builder */
    public static class DiscordRequest extends Request {
        /** The connection of this Request */
        public final HttpURLConnection conn;

        /**
         * Builds a GET request with the specified QueryBuilder
         * @param builder QueryBuilder
         * @throws IOException If an I/O exception occurs
         */
        public DiscordRequest(QueryBuilder builder) throws IOException, NoSuchFieldException, IllegalAccessException {
            this(builder.toString(), "GET");
        }

        /**
         * Builds a GET request with the specified url
         * @param url Url
         * @throws IOException If an I/O exception occurs
         */
        public DiscordRequest(String url) throws IOException, NoSuchFieldException, IllegalAccessException {
            this(url, "GET");
        }

        /**
         * Builds a request with the specified url and method
         * @param url Url
         * @param method <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods">HTTP method</a>
         * @throws IOException If an I/O exception occurs
         */
        public DiscordRequest(String url, String method) throws IOException, NoSuchFieldException, IllegalAccessException {
            super(!url.startsWith("http") ? "https://discord.com/api/v9" + url : url, method);
            conn = (HttpURLConnection) new URL(!url.startsWith("http") ? "https://discord.com/api/v9" + url : url).openConnection();
            conn.setRequestMethod(method.toUpperCase());
            conn.addRequestProperty("Authorization", (String) ReflectUtils.getField(StoreStream.getAuthentication(), "authToken"));
            conn.addRequestProperty("User-Agent", RestAPI.AppHeadersProvider.INSTANCE.getUserAgent());
            conn.addRequestProperty("X-Super-Properties", AnalyticSuperProperties.INSTANCE.getSuperPropertiesStringBase64());
            conn.addRequestProperty("Accept", "*/*");
        }
    }

    /**
     * Send a simple GET request
     * @param url The url to fetch
     * @return Raw response (String). If you want Json, use simpleJsonGet
     */
    public static String simpleGet(String url) throws IOException {
        return new Request(url, "GET").execute().text();
    }

    /**
     * Download content from the specified url to the specified {@link File}
     * @param url The url to download content from
     * @param outputFile The file to save to
     */
    public static void simpleDownload(String url, File outputFile) throws IOException {
        new Request(url).execute().saveToFile(outputFile);
    }

    /**
     * Send a simple GET request
     * @param url The url to fetch
     * @param schema Class to <a href="https://en.wikipedia.org/wiki/Serialization">deserialize</a> the response into
     * @return Response Object
     */
    public static <T> T simpleJsonGet(String url, Type schema) throws IOException {
        String res = simpleGet(url);
        return GsonUtils.fromJson(res, schema);
    }

    /**
     * Send a simple GET request
     * @param url The url to fetch
     * @param schema Class to <a href="https://en.wikipedia.org/wiki/Serialization">deserialize</a> the response into
     * @return Response Object
     */
    public static <T> T simpleJsonGet(String url, Class<T> schema) throws IOException {
        return simpleJsonGet(url, (Type) schema);
    }

    /**
     * Send a simple POST request
     * @param url The url to fetch
     * @param body The request body
     * @return Raw response (String). If you want Json, use simpleJsonPost
     */
    public static String simplePost(String url, String body) throws IOException {
        return new Request(url, "POST").executeWithBody(body).text();
    }

    /**
     * Send a simple POST request and parse the JSON response
     * @param url The url to fetch
     * @param body The request body
     * @param schema Class to <a href="https://en.wikipedia.org/wiki/Serialization">deserialize</a> the response into
     * @return Response deserialized into the provided Class
     */
    public static <T> T simpleJsonPost(String url, String body, Type schema) throws IOException {
        String res = simplePost(url, body);
        return GsonUtils.fromJson(res, schema);
    }

    // This is just here for proper Generics so you can do simpleJsonPost(url, body, myClass).myMethod() without having to cast
    /**
     * Send a simple POST request and parse the JSON response
     * @param url The url to fetch
     * @param body The request body
     * @param schema Class to <a href="https://en.wikipedia.org/wiki/Serialization">deserialize</a> the response into
     * @return Response deserialized into the provided Class
     */
    public static <T> T simpleJsonPost(String url, String body, Class<T> schema) throws IOException {
        return simpleJsonPost(url, body, (Type) schema);
    }

    /**
     * Send a simple POST request with JSON body
     * @param url The url to fetch
     * @param body The request body
     * @param schema Class to <a href="https://en.wikipedia.org/wiki/Serialization">deserialize</a> the response into
     * @return Response deserialized into the provided Class
     */
    public static <T> T simpleJsonPost(String url, Object body, Type schema) throws IOException {
        return new Request(url).executeWithJson(body).json(schema);
    }

    // This is just here for proper Generics so you can do simpleJsonPost(url, body, myClass).myMethod() without having to cast
    /**
     * Send a simple POST request with JSON body
     * @param url The url to fetch
     * @param body The request body
     * @param schema Class to <a href="https://en.wikipedia.org/wiki/Serialization">deserialize</a> the response into
     * @return Response deserialized into the provided Class
     */
    public static <T> T simpleJsonPost(String url, Object body, Class<T> schema) throws IOException {
        return simpleJsonPost(url, body, (Type) schema);
    }
}
