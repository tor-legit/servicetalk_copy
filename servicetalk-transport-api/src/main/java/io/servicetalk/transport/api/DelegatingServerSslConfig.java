/*
 * Copyright © 2021 Apple Inc. and the ServiceTalk project authors
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
package io.servicetalk.transport.api;

/**
 * Wrap a {@link ServerSslConfig} and delegate all methods to it.
 */
public class DelegatingServerSslConfig extends DelegatingSslConfig<ServerSslConfig> implements ServerSslConfig {
    /**
     * Create a new instance.
     * @param delegate The instance to delegate to.
     */
    protected DelegatingServerSslConfig(final ServerSslConfig delegate) {
        super(delegate);
    }

    @Override
    public SslClientAuthMode clientAuthMode() {
        return delegate().clientAuthMode();
    }
}
