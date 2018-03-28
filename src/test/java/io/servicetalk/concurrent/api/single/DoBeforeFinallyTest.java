/*
 * Copyright © 2018 Apple Inc. and the ServiceTalk project authors
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
package io.servicetalk.concurrent.api.single;

import io.servicetalk.concurrent.api.DeliberateException;
import io.servicetalk.concurrent.api.Single;

import org.junit.Test;

import static io.servicetalk.concurrent.api.DeliberateException.DELIBERATE_EXCEPTION;

public class DoBeforeFinallyTest extends AbstractDoFinallyTest {
    @Override
    protected <T> Single<T> doFinally(Single<T> single, Runnable runnable) {
        return single.doBeforeFinally(runnable);
    }

    @Test
    @Override
    public void testCallbackThrowsErrorOnSuccess() {
        listener.listen(doFinally(Single.success("Hello"), () -> {
            throw DELIBERATE_EXCEPTION;
        })).verifyFailure(DELIBERATE_EXCEPTION);
    }

    @Test
    @Override
    public void testCallbackThrowsErrorOnError() {
        DeliberateException exception = new DeliberateException();
        listener.listen(doFinally(Single.error(DELIBERATE_EXCEPTION), () -> {
            throw exception;
        })).verifyFailure(exception).verifySuppressedFailure(DELIBERATE_EXCEPTION);
    }
}
