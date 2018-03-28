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
package io.servicetalk.concurrent.api;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.function.Function;
import javax.annotation.Nullable;

import static java.util.Objects.requireNonNull;

/**
 * As returned by {@link Publisher#onErrorResume(Function)}.
 *
 * @param <T> Type of items emitted by this {@link Publisher}.
 */
final class ResumePublisher<T> extends Publisher<T> {

    private final Publisher<T> first;
    private final Function<Throwable, Publisher<T>> nextFactory;

    ResumePublisher(Publisher<T> first, Function<Throwable, Publisher<T>> nextFactory) {
        this.first = requireNonNull(first);
        this.nextFactory = requireNonNull(nextFactory);
    }

    @Override
    protected void handleSubscribe(Subscriber<? super T> subscriber) {
        first.subscribe(new ResumeSubscriber<>(subscriber, nextFactory));
    }

    private static final class ResumeSubscriber<T> implements Subscriber<T> {
        private final Subscriber<? super T> subscriber;
        @Nullable
        private volatile Function<Throwable, Publisher<T>> nextFactory;
        @Nullable
        private volatile SequentialSubscription sequentialSubscription;

        ResumeSubscriber(Subscriber<? super T> subscriber, Function<Throwable, Publisher<T>> nextFactory) {
            this.subscriber = subscriber;
            this.nextFactory = nextFactory;
        }

        @Override
        public void onSubscribe(Subscription s) {
            SequentialSubscription sequentialSubscription = this.sequentialSubscription;
            if (sequentialSubscription == null) {
                this.sequentialSubscription = sequentialSubscription = new SequentialSubscription(s);
                subscriber.onSubscribe(sequentialSubscription);
            } else {
                // Only a single re-subscribe is allowed.
                nextFactory = null;
                sequentialSubscription.switchTo(s);
            }
        }

        @Override
        public void onNext(T t) {
            SequentialSubscription sequentialSubscription = this.sequentialSubscription;
            assert sequentialSubscription != null : "Subscription can not be null in onNext.";
            sequentialSubscription.itemReceived();
            subscriber.onNext(t);
        }

        @Override
        public void onError(Throwable t) {
            final Function<Throwable, Publisher<T>> nextFactory = this.nextFactory;
            if (nextFactory == null) {
                subscriber.onError(t);
                return;
            }

            final Publisher<T> next;
            try {
                next = requireNonNull(nextFactory.apply(t));
            } catch (Throwable throwable) {
                throwable.addSuppressed(t);
                subscriber.onError(throwable);
                return;
            }
            next.subscribe(this);
        }

        @Override
        public void onComplete() {
            subscriber.onComplete();
        }
    }
}
