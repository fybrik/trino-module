/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.proxy;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.ListenableFuture;

import org.weakref.jmx.internal.guava.base.Splitter;

import io.airlift.http.client.HttpClient;
import io.airlift.http.client.Request;
import io.airlift.log.Logger;
import io.airlift.units.Duration;
import io.trino.proxy.ProxyResponseHandler.ProxyResponse;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import static com.fasterxml.jackson.core.JsonFactory.Feature.CANONICALIZE_FIELD_NAMES;
import static com.fasterxml.jackson.core.JsonToken.END_OBJECT;
import static com.fasterxml.jackson.core.JsonToken.FIELD_NAME;
import static com.fasterxml.jackson.core.JsonToken.START_OBJECT;
import static com.fasterxml.jackson.core.JsonToken.VALUE_STRING;
import static com.google.common.hash.Hashing.hmacSha256;
import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static com.google.common.net.HttpHeaders.COOKIE;
import static com.google.common.net.HttpHeaders.SET_COOKIE;
import static com.google.common.net.HttpHeaders.USER_AGENT;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.airlift.http.client.HttpUriBuilder.uriBuilderFrom;
import static io.airlift.http.client.Request.Builder.prepareDelete;
import static io.airlift.http.client.Request.Builder.prepareGet;
import static io.airlift.http.client.Request.Builder.preparePost;
import static io.airlift.http.client.StaticBodyGenerator.createStaticBodyGenerator;
import static io.airlift.jaxrs.AsyncResponseHandler.bindAsyncResponse;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readAllBytes;
import static java.util.Collections.list;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.TimeUnit.MINUTES;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN_TYPE;
import static javax.ws.rs.core.Response.Status.BAD_GATEWAY;
import static javax.ws.rs.core.Response.Status.FORBIDDEN;
import static javax.ws.rs.core.Response.noContent;

@Path("/")
public class ProxyResource
{
    private static final Logger log = Logger.get(ProxyResource.class);

    private static final String X509_ATTRIBUTE = "javax.servlet.request.X509Certificate";
    private static final Duration ASYNC_TIMEOUT = new Duration(2, MINUTES);
    private static final JsonFactory JSON_FACTORY = new JsonFactoryBuilder().disable(CANONICALIZE_FIELD_NAMES).build();

    private final ExecutorService executor = newCachedThreadPool(daemonThreadsNamed("proxy-%s"));
    private final HttpClient httpClient;
    private final JsonWebTokenHandler jwtHandler;
    private final URI remoteUri;
    private final HashFunction hmac;
    private final Map<String, String> mapTableToView;

    @Inject
    public ProxyResource(@ForProxy HttpClient httpClient, JsonWebTokenHandler jwtHandler, ProxyConfig config)
    {
        this.httpClient = requireNonNull(httpClient, "httpClient is null");
        this.jwtHandler = requireNonNull(jwtHandler, "jwtHandler is null");
        this.remoteUri = requireNonNull(config.getUri(), "uri is null");
        this.hmac = hmacSha256(loadSharedSecret(config.getSharedSecretFile()));
        String tableToView = config.getViewMap();
        Map<String, String> mapTableToView = Splitter.on(",")
            .withKeyValueSeparator(":")
            .split(tableToView);
        System.out.printf("ProxyResource config view = %s, this.hmac = %s", mapTableToView, this.hmac);
        for (Map.Entry<String, String> entry : mapTableToView.entrySet()) {
            System.out.println(entry.getKey() + ":" + entry.getValue().toString());
        }
        this.mapTableToView = mapTableToView;
    }

    @PreDestroy
    public void shutdown()
    {
        executor.shutdownNow();
    }

    @GET
    @Path("/v1/info")
    @Produces(APPLICATION_JSON)
    public void getInfo(
            @Context HttpServletRequest servletRequest,
            @Suspended AsyncResponse asyncResponse)
    {
        // System.out.printf("getInfo servletRequest = %s", servletRequest);
        Request.Builder request = prepareGet()
                .setUri(uriBuilderFrom(remoteUri).replacePath("/v1/info").build());

        performRequest(servletRequest, asyncResponse, request, response ->
                responseWithHeaders(Response.ok(response.getBody()), response));
    }

    @POST
    @Path("/v1/statement")
    @Produces(APPLICATION_JSON)
    public void postStatement(
            String statement,
            @Context HttpServletRequest servletRequest,
            @Context UriInfo uriInfo,
            @Suspended AsyncResponse asyncResponse)
    {
        System.out.printf("postStatement statement = %s", statement);
        // System.out.printf("postStatement first table = %s", this.mapTableToView.keySet().toArray()[0].toString());
        String user = "";

        ArrayList<String> headers = list(servletRequest.getHeaderNames());
        if(headers.contains("X-Trino-User")) {
                // System.out.printf("postStatement X-Trino-User exists");
                for (String value : list(servletRequest.getHeaders("X-Trino-User"))) {
                    user = value;
                    // System.out.printf("postStatement headers values = %s", user);
            }
        }
        if(user.equals("user1")) {
            

            for (Map.Entry<String, String> entry : mapTableToView.entrySet()) {
                String tableName = entry.getKey();
                String viewName = entry.getValue();
                if(statement.contains(tableName)) {
                    statement = statement.replace(tableName, viewName);
                    break;
                }
            }

            System.out.printf("postStatement statement = %s", statement);

        }
        
        Request.Builder request = preparePost()
                .setUri(uriBuilderFrom(remoteUri).replacePath("/v1/statement").build())
                .setBodyGenerator(createStaticBodyGenerator(statement, UTF_8));

        // String trinoUser = request.getHeader("X-Trino-User");
        // System.out.printf("performRequest func request = %s, trinoUser = %s", request, trinoUser);
        performRequest(servletRequest, asyncResponse, request, response -> buildResponse(uriInfo, response));
    }

    @GET
    @Path("/v1/proxy")
    @Produces(APPLICATION_JSON)
    public void getNext(
            @QueryParam("uri") String uri,
            @QueryParam("hmac") String hash,
            @Context HttpServletRequest servletRequest,
            @Context UriInfo uriInfo,
            @Suspended AsyncResponse asyncResponse)
    {
        // System.out.printf("getNext uri = %s, servletRequest = %s, uriInfo = %s, hash = %s", uri, servletRequest, uriInfo, hash);
        // System.out.printf("getNext hashCode = %s, hashString = %s", HashCode.fromString(hash), hmac.hashString(uri, UTF_8));
        if (!hmac.hashString(uri, UTF_8).equals(HashCode.fromString(hash))) {
            throw badRequest(FORBIDDEN, "Failed to validate HMAC of URI");
        }

        Request.Builder request = prepareGet().setUri(URI.create(uri));

        performRequest(servletRequest, asyncResponse, request, response -> buildResponse(uriInfo, response));
    }

    @DELETE
    @Path("/v1/proxy")
    @Produces(APPLICATION_JSON)
    public void cancelQuery(
            @QueryParam("uri") String uri,
            @QueryParam("hmac") String hash,
            @Context HttpServletRequest servletRequest,
            @Suspended AsyncResponse asyncResponse)
    {
        if (!hmac.hashString(uri, UTF_8).equals(HashCode.fromString(hash))) {
            throw badRequest(FORBIDDEN, "Failed to validate HMAC of URI");
        }

        Request.Builder request = prepareDelete().setUri(URI.create(uri));

        performRequest(servletRequest, asyncResponse, request, response -> responseWithHeaders(noContent(), response));
    }

    private void performRequest(
            HttpServletRequest servletRequest,
            AsyncResponse asyncResponse,
            Request.Builder requestBuilder,
            Function<ProxyResponse, Response> responseBuilder)
    {
        // System.out.printf("performRequest func servletRequest = %s", servletRequest);
        setupBearerToken(servletRequest, requestBuilder);

        for (String name : list(servletRequest.getHeaderNames())) {
            // System.out.printf("performRequest headers = %s", name);
            if (isTrinoHeader(name) || name.equalsIgnoreCase(COOKIE)) {
                for (String value : list(servletRequest.getHeaders(name))) {
                    // System.out.printf("performRequest headers values = %s", value);
                    requestBuilder.addHeader(name, value);
                }
            }
            else if (name.equalsIgnoreCase(USER_AGENT)) {
                for (String value : list(servletRequest.getHeaders(name))) {
                    requestBuilder.addHeader(name, "[Trino Proxy] " + value);
                }
            }
        }

        Request request = requestBuilder
                .setPreserveAuthorizationOnRedirect(true)
                .build();
        String trinoUser = request.getHeader("X-Trino-User");
        System.out.printf("performRequest trinoUser = %s", trinoUser);
        ListenableFuture<Response> future = executeHttp(request)
                .transform(responseBuilder::apply, executor)
                .catching(ProxyException.class, e -> handleProxyException(request, e), directExecutor());

        setupAsyncResponse(asyncResponse, future);
    }

    private Response buildResponse(UriInfo uriInfo, ProxyResponse response)
    {
        byte[] body = rewriteResponse(response.getBody(), uri -> rewriteUri(uriInfo, uri));
        return responseWithHeaders(Response.ok(body), response);
    }

    private String rewriteUri(UriInfo uriInfo, String uri)
    {
        // System.out.printf("rewriteUri uri = %s", uri);
        return uriInfo.getAbsolutePathBuilder()
                .replacePath("/v1/proxy")
                .queryParam("uri", uri)
                .queryParam("hmac", hmac.hashString(uri, UTF_8))
                .build()
                .toString();
    }

    private void setupAsyncResponse(AsyncResponse asyncResponse, ListenableFuture<Response> future)
    {
        bindAsyncResponse(asyncResponse, future, executor)
                .withTimeout(ASYNC_TIMEOUT, () -> Response
                        .status(BAD_GATEWAY)
                        .type(TEXT_PLAIN_TYPE)
                        .entity("Request to remote Trino server timed out after" + ASYNC_TIMEOUT)
                        .build());
    }

    private FluentFuture<ProxyResponse> executeHttp(Request request)
    {
        // System.out.printf("executeHttp func, request = %s", request);
        return FluentFuture.from(httpClient.executeAsync(request, new ProxyResponseHandler()));
    }

    private void setupBearerToken(HttpServletRequest servletRequest, Request.Builder requestBuilder)
    {
        if (!jwtHandler.isConfigured()) {
            return;
        }

        X509Certificate[] certs = (X509Certificate[]) servletRequest.getAttribute(X509_ATTRIBUTE);
        if ((certs == null) || (certs.length == 0)) {
            throw badRequest(FORBIDDEN, "No TLS certificate present for request");
        }
        String principal = certs[0].getSubjectX500Principal().getName();

        String accessToken = jwtHandler.getBearerToken(principal);
        requestBuilder.addHeader(AUTHORIZATION, "Bearer " + accessToken);
    }

    private static <T> T handleProxyException(Request request, ProxyException e)
    {
        log.warn(e, "Proxy request failed: %s %s", request.getMethod(), request.getUri());
        throw badRequest(BAD_GATEWAY, e.getMessage());
    }

    private static WebApplicationException badRequest(Status status, String message)
    {
        throw new WebApplicationException(
                Response.status(status)
                        .type(TEXT_PLAIN_TYPE)
                        .entity(message)
                        .build());
    }

    private static boolean isTrinoHeader(String name)
    {
        return name.toLowerCase(ENGLISH).startsWith("x-trino-");
    }

    private static Response responseWithHeaders(ResponseBuilder builder, ProxyResponse response)
    {
        response.getHeaders().forEach((headerName, value) -> {
            String name = headerName.toString();
            if (isTrinoHeader(name) || name.equalsIgnoreCase(SET_COOKIE)) {
                builder.header(name, value);
            }
        });
        return builder.build();
    }

    private static byte[] rewriteResponse(byte[] input, Function<String, String> uriRewriter)
    {
        try {
            JsonParser parser = JSON_FACTORY.createParser(input);
            ByteArrayOutputStream out = new ByteArrayOutputStream(input.length * 2);
            JsonGenerator generator = JSON_FACTORY.createGenerator(out);

            JsonToken token = parser.nextToken();
            if (token != START_OBJECT) {
                throw invalidJson("bad start token: " + token);
            }
            generator.copyCurrentEvent(parser);

            while (true) {
                token = parser.nextToken();
                if (token == null) {
                    throw invalidJson("unexpected end of stream");
                }

                if (token == END_OBJECT) {
                    generator.copyCurrentEvent(parser);
                    break;
                }

                if (token == FIELD_NAME) {
                    String name = parser.getValueAsString();
                    if (!"nextUri".equals(name) && !"partialCancelUri".equals(name)) {
                        generator.copyCurrentStructure(parser);
                        continue;
                    }

                    token = parser.nextToken();
                    if (token != VALUE_STRING) {
                        throw invalidJson(format("bad %s token: %s", name, token));
                    }
                    String value = parser.getValueAsString();

                    value = uriRewriter.apply(value);
                    generator.writeStringField(name, value);
                    continue;
                }

                throw invalidJson("unexpected token: " + token);
            }

            token = parser.nextToken();
            if (token != null) {
                throw invalidJson("unexpected token after object close: " + token);
            }

            generator.close();
            return out.toByteArray();
        }
        catch (IOException e) {
            throw new ProxyException(e);
        }
    }

    private static IOException invalidJson(String message)
    {
        return new IOException("Invalid JSON response from remote Trino server: " + message);
    }

    private static byte[] loadSharedSecret(File file)
    {
        try {
            return Base64.getMimeDecoder().decode(readAllBytes(file.toPath()));
        }
        catch (IOException | IllegalArgumentException e) {
            throw new RuntimeException("Failed to load shared secret file: " + file, e);
        }
    }
}
