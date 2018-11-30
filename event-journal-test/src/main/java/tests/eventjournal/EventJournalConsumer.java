/*
 * Copyright (c) 2008-2018, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tests.eventjournal;

import com.hazelcast.client.proxy.ClientMapProxy;
import com.hazelcast.core.ExecutionCallback;
import com.hazelcast.core.OperationTimeoutException;
import com.hazelcast.jet.impl.util.ExceptionUtil;
import com.hazelcast.map.journal.EventJournalMapEvent;
import com.hazelcast.ringbuffer.ReadResultSet;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;


public class EventJournalConsumer<K, V> {

    private static final int POLL_COUNT = 20;

    private final ClientMapProxy<K, V> proxy;
    private final int partitionCount;
    private AtomicReference<Throwable> failure;

    public EventJournalConsumer(ClientMapProxy<K, V> proxy, int partitionCount, AtomicReference<Throwable> failure) {
        this.proxy = proxy;
        this.partitionCount = partitionCount;
        this.failure = failure;
    }

    public void drain(Consumer<EventJournalMapEvent<K, V>> consumer) {
        for (int i = 0; i < partitionCount; i++) {
            readFromJournal(0, i, consumer);
        }
    }

    private void readFromJournal(long seq, int pid,
                                 Consumer<EventJournalMapEvent<K, V>> consumer
    ) {
        proxy.<EventJournalMapEvent<K, V>>readFromEventJournal(
                seq, 1, POLL_COUNT, pid, null, null
        ).andThen(new ExecutionCallback<ReadResultSet<EventJournalMapEvent<K, V>>>() {
            @Override
            public void onResponse(ReadResultSet<EventJournalMapEvent<K, V>> response) {
                readFromJournal(response.getNextSequenceToReadFrom(), pid, consumer);
                try {
                    response.forEach(consumer);
                } catch (Throwable t) {
                    failure.set(t);
                }
                long prevSequence = seq;
                long lostCount = response.getNextSequenceToReadFrom() - response.readCount() - prevSequence;
                if (lostCount > 0) {
                    System.out.println(lostCount + " events lost for partition "
                            + pid + " due to journal overflow when reading from event journal."
                            + " Increase journal size to avoid this error. nextSequenceToReadFrom="
                            + response.getNextSequenceToReadFrom() + ", readCount=" + response.readCount()
                            + ", prevSeq=" + prevSequence);
                }
            }
            @Override
            public void onFailure(Throwable t) {
                Throwable peeled = ExceptionUtil.peel(t);
                if (peeled instanceof OperationTimeoutException) {
                    readFromJournal(seq, pid, consumer);
                    return;
                }
                t.printStackTrace();
                failure.set(t);
            }
        });
    }

}
