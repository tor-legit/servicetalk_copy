/*
 * Copyright © 2019, 2021 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.http.api;

import io.servicetalk.concurrent.api.Single;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.security.Principal;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

import static io.servicetalk.concurrent.api.Single.failed;
import static io.servicetalk.concurrent.api.Single.succeeded;
import static io.servicetalk.http.api.AbstractHttpRequesterFilterTest.RequesterType.Client;
import static io.servicetalk.http.api.AbstractHttpRequesterFilterTest.SecurityType.Insecure;
import static io.servicetalk.http.api.AbstractHttpRequesterFilterTest.SecurityType.Secure;
import static io.servicetalk.http.api.HttpResponseStatus.OK;
import static io.servicetalk.http.api.HttpResponseStatus.UNAUTHORIZED;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasToString;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * This is a test-case for the {@link AbstractHttpRequesterFilterTest} HTTP request filter test utilities.
 */
class SimpleHttpRequesterFilterTest extends AbstractHttpRequesterFilterTest {

    private static final class HeaderEnrichingRequestFilter implements StreamingHttpClientFilterFactory,
                                                                       StreamingHttpConnectionFilterFactory {
        @Override
        public StreamingHttpClientFilter create(final FilterableStreamingHttpClient client) {
            return new StreamingHttpClientFilter(client) {
                @Override
                protected Single<StreamingHttpResponse> request(final StreamingHttpRequester delegate,
                                                                final StreamingHttpRequest request) {
                    return HeaderEnrichingRequestFilter.request(delegate, null, request);
                }

                @Override
                public Single<? extends FilterableReservedStreamingHttpConnection> reserveConnection(
                        final HttpRequestMetaData metaData) {
                    return delegate().reserveConnection(metaData).map(r ->
                            new ReservedStreamingHttpConnectionFilter(r) {
                                @Override
                                public Single<StreamingHttpResponse> request(final StreamingHttpRequest request) {
                                    return HeaderEnrichingRequestFilter.request(
                                            delegate(), connectionContext(), request);
                                }
                            });
                }
            };
        }

        @Override
        public StreamingHttpConnectionFilter create(final FilterableStreamingHttpConnection connection) {
            return new StreamingHttpConnectionFilter(connection) {
                @Override
                public Single<StreamingHttpResponse> request(final StreamingHttpRequest request) {
                    return HeaderEnrichingRequestFilter.request(delegate(), connectionContext(), request);
                }
            };
        }

        private static Single<StreamingHttpResponse> request(final StreamingHttpRequester delegate,
                                                             @Nullable final HttpConnectionContext context,
                                                             final StreamingHttpRequest request) {
            request.setHeader("X-Unit", "Test");
            if (context != null) {
                request.setHeader("X-Local", context.localAddress().toString());
                request.setHeader("X-Remote", context.remoteAddress().toString());
                if (context.sslSession() != null) {
                    request.setHeader("X-Secure", "True");
                }
            }
            return delegate.request(request);
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {0}-{1}")
    @MethodSource("requesterTypes")
    void headersEnrichedByFilter(final RequesterType type, final SecurityType security) {
        setUp(security);
        StreamingHttpRequester filter = createFilter(type, new HeaderEnrichingRequestFilter());
        StreamingHttpRequest request = filter.get("/");
        filter.request(request);

        assertThat(request.headers().get("X-Unit"), hasToString("Test"));
        if (type != Client) {
            assertThat(request.headers().get("X-Local"), hasToString(localAddress().toString()));
            assertThat(request.headers().get("X-Remote"), hasToString(remoteAddress().toString()));
            if (security == Secure) {
                assertThat(request.headers().get("X-Secure"), hasToString("True"));
            }
        }
    }

    private static final class InterceptingRequestFilter
            implements StreamingHttpClientFilterFactory, StreamingHttpConnectionFilterFactory {

        final AtomicInteger requestCalls = new AtomicInteger();

        @Override
        public StreamingHttpClientFilter create(final FilterableStreamingHttpClient client) {
            return new StreamingHttpClientFilter(client) {

                @Override
                protected Single<StreamingHttpResponse> request(final StreamingHttpRequester delegate,
                                                                final StreamingHttpRequest request) {
                    return InterceptingRequestFilter.this.request(delegate);
                }

                @Override
                public Single<? extends FilterableReservedStreamingHttpConnection> reserveConnection(
                        final HttpRequestMetaData metaData) {
                    return delegate().reserveConnection(metaData)
                            .map(r -> new ReservedStreamingHttpConnectionFilter(r) {
                                @Override
                                public Single<StreamingHttpResponse> request(final StreamingHttpRequest request) {
                                    return InterceptingRequestFilter.this.request(delegate());
                                }
                            });
                }
            };
        }

        @Override
        public StreamingHttpConnectionFilter create(final FilterableStreamingHttpConnection connection) {
            return new StreamingHttpConnectionFilter(connection) {
                @Override
                public Single<StreamingHttpResponse> request(final StreamingHttpRequest request) {
                    return InterceptingRequestFilter.this.request(delegate());
                }
            };
        }

        private Single<StreamingHttpResponse> request(final StreamingHttpRequester delegate) {
            requestCalls.incrementAndGet();
            return succeeded(delegate.httpResponseFactory().ok());
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {0}-{1}")
    @MethodSource("requesterTypes")
    void requestInterceptedByFilter(final RequesterType type, final SecurityType security) {
        setUp(security);
        InterceptingRequestFilter filterFactory = new InterceptingRequestFilter();
        StreamingHttpRequester filter = createFilter(type,
                (respFactory, request) -> {
                    fail("Filter should have intercepted this request() call");
                    return null;
                },
                (respFactory, context, request) -> {
                    fail("Filter should have intercepted this request() call");
                    return null;
                }, filterFactory);
        filter.request(filter.get("/"));
        assertThat(filterFactory.requestCalls.get(), equalTo(1));
    }

    /**
     * Simple SSL {@link Principal} verifying filter that should be applied as both connection-filter and client-filter
     * at the same time to ensure full coverage of all code paths.
     */
    private static final class SecurityEnforcingFilter implements StreamingHttpClientFilterFactory,
                                                                  StreamingHttpConnectionFilterFactory {
        @Override
        public StreamingHttpClientFilter create(final FilterableStreamingHttpClient client) {
            return new StreamingHttpClientFilter(client) {
                @Override
                public Single<? extends FilterableReservedStreamingHttpConnection> reserveConnection(
                        final HttpRequestMetaData metaData) {
                    return delegate().reserveConnection(metaData)
                            .map(r -> new ReservedStreamingHttpConnectionFilter(r) {
                                @Override
                                public Single<StreamingHttpResponse> request(final StreamingHttpRequest request) {
                                    return SecurityEnforcingFilter.request(
                                            delegate(), connectionContext(), request);
                                }
                            });
                }
            };
        }

        @Override
        public StreamingHttpConnectionFilter create(final FilterableStreamingHttpConnection connection) {
            return new StreamingHttpConnectionFilter(connection) {
                @Override
                public Single<StreamingHttpResponse> request(final StreamingHttpRequest request) {
                    return SecurityEnforcingFilter.request(delegate(), connectionContext(), request);
                }
            };
        }

        private static Single<StreamingHttpResponse> request(final StreamingHttpRequester delegate,
                                                             final HttpConnectionContext context,
                                                             final StreamingHttpRequest request) {
            try {
                final SSLSession sslSession = context.sslSession();
                if (sslSession != null && sslSession.getPeerPrincipal() != null
                        && sslSession.getPeerPrincipal().getName().equals("unit.test.auth")) {
                    // proper SSL Session established, continue with delegation
                    return delegate.request(request);
                }
            } catch (SSLPeerUnverifiedException e) {
                return failed(e);
            }

            return succeeded(delegate.httpResponseFactory().unauthorized());
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {0}-{1}")
    @MethodSource("requesterTypes")
    void unauthorizedConnectionRefusingFilterWithInvalidPrincipal(final RequesterType type,
                                                                  final SecurityType security) throws Exception {
        assumeFalse(type == Client, "Clients don't carry SSL Context");
        setUp(security);

        BlockingHttpRequester filter = asBlockingRequester(createFilter(type, new SecurityEnforcingFilter()));
        HttpResponse resp = filter.request(filter.get("/"));
        assertThat(resp.status(), equalTo(UNAUTHORIZED));
    }

    @ParameterizedTest(name = "{displayName} [{index}] {0}-{1}")
    @MethodSource("requesterTypes")
    void unauthorizedConnectionRefusingFilterWithValidPrincipal(final RequesterType type,
                                                                final SecurityType security) throws Exception {
        assumeFalse(type == Client, "Clients don't carry SSL Context");
        setUp(security);
        final Principal principal = mock(Principal.class);
        lenient().when(principal.getName()).thenReturn("unit.test.auth");
        lenient().when(sslSession().getPeerPrincipal()).thenReturn(principal);

        BlockingHttpRequester filter = asBlockingRequester(createFilter(type, new SecurityEnforcingFilter()));
        HttpResponse resp = filter.request(filter.get("/"));

        if (security == Insecure) {
            assertThat(resp.status(), equalTo(UNAUTHORIZED));
        } else {
            assertThat(resp.status(), equalTo(OK));
        }
    }
}
