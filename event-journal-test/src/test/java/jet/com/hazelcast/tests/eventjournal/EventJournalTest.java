/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
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

package jet.com.hazelcast.tests.eventjournal;

import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.ClientUserCodeDeploymentConfig;
import com.hazelcast.client.impl.HazelcastClientInstanceImpl;
import com.hazelcast.client.proxy.ClientMapProxy;
import com.hazelcast.config.Config;
import com.hazelcast.config.EventJournalConfig;
import com.hazelcast.config.GroupConfig;
import com.hazelcast.core.MembershipAdapter;
import com.hazelcast.core.MembershipEvent;
import com.hazelcast.jet.Jet;
import com.hazelcast.jet.JetInstance;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.accumulator.LongAccumulator;
import com.hazelcast.jet.aggregate.AggregateOperation1;
import com.hazelcast.jet.aggregate.AggregateOperations;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.config.ProcessingGuarantee;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.TimestampKind;
import com.hazelcast.jet.core.Vertex;
import com.hazelcast.jet.core.WatermarkGenerationParams;
import com.hazelcast.jet.core.WindowDefinition;
import com.hazelcast.jet.core.processor.SinkProcessors;
import com.hazelcast.jet.server.JetBootstrap;
import com.hazelcast.map.journal.EventJournalMapEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import tests.eventjournal.EventJournalConsumer;
import tests.eventjournal.EventJournalTradeProducer;
import tests.snapshot.QueueVerifier;

import java.io.Serializable;

import static com.hazelcast.core.EntryEventType.ADDED;
import static com.hazelcast.core.EntryEventType.UPDATED;
import static com.hazelcast.jet.JournalInitialPosition.START_FROM_OLDEST;
import static com.hazelcast.jet.core.Edge.between;
import static com.hazelcast.jet.core.JobStatus.COMPLETED;
import static com.hazelcast.jet.core.Partitioner.HASH_CODE;
import static com.hazelcast.jet.core.WatermarkEmissionPolicy.emitByFrame;
import static com.hazelcast.jet.core.WatermarkGenerationParams.wmGenParams;
import static com.hazelcast.jet.core.WatermarkPolicies.withFixedLag;
import static com.hazelcast.jet.core.WindowDefinition.slidingWindowDef;
import static com.hazelcast.jet.core.processor.Processors.accumulateByFrameP;
import static com.hazelcast.jet.core.processor.Processors.combineToSlidingWindowP;
import static com.hazelcast.jet.core.processor.SourceProcessors.streamMapP;
import static com.hazelcast.jet.function.DistributedFunctions.entryKey;
import static com.hazelcast.jet.function.DistributedFunctions.wholeItem;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;

@RunWith(JUnit4.class)
public class EventJournalTest implements Serializable {

    private transient JetInstance jet;
    private String mapName;
    private String resultsMapName;
    private long durationInMillis;
    private int countPerTicker;
    private int snapshotIntervalMs;
    private int lagMs;
    private int windowSize;
    private int slideBy;
    private int partitionCount;
    private EventJournalTradeProducer tradeProducer;

    public static void main(String[] args) {
        JUnitCore.main(EventJournalTest.class.getName());
    }

    @Before
    public void setUp() {
        String isolatedClientConfig = System.getProperty("isolatedClientConfig");
        if (isolatedClientConfig != null) {
            System.setProperty("hazelcast.client.config", isolatedClientConfig);
        }
        mapName = System.getProperty("map", String.format("%s-%d", "event-journal", System.currentTimeMillis()));
        resultsMapName = mapName + "-results";
        lagMs = Integer.parseInt(System.getProperty("lagMs", "2000"));
        snapshotIntervalMs = Integer.parseInt(System.getProperty("snapshotIntervalMs", "500"));
        windowSize = Integer.parseInt(System.getProperty("windowSize", "20"));
        slideBy = Integer.parseInt(System.getProperty("slideBy", "10"));
        countPerTicker = Integer.parseInt(System.getProperty("countPerTicker", "100"));
        durationInMillis = MINUTES.toMillis(Integer.parseInt(System.getProperty("durationInMinutes", "15")));
        jet = JetBootstrap.getInstance();
        tradeProducer = new EventJournalTradeProducer(countPerTicker, jet.getMap(mapName));
        partitionCount = jet.getHazelcastInstance().getPartitionService().getPartitions().size();
        Config config = jet.getHazelcastInstance().getConfig();
        EventJournalConfig eventJournalConfig = new EventJournalConfig()
                .setMapName(mapName + "*").setEnabled(true).setCapacity(partitionCount * 200_000);
        config.addEventJournalConfig(eventJournalConfig);
        deployResources();

    }

    @Test
    public void eventJournalTest() throws Exception {
        JobConfig jobConfig = new JobConfig();
        jobConfig.setSnapshotIntervalMillis(snapshotIntervalMs);
        jobConfig.setProcessingGuarantee(ProcessingGuarantee.EXACTLY_ONCE);
        Job job = jet.newJob(testDAG(), jobConfig);
        tradeProducer.start();

        addMembershipListenerForRestart(job);

        int windowCount = windowSize / slideBy;
        QueueVerifier queueVerifier = new QueueVerifier(resultsMapName, windowCount).startVerification();

        ClientMapProxy<Long, Long> resultMap = (ClientMapProxy) jet.getHazelcastInstance().getMap(resultsMapName);
        EventJournalConsumer<Long, Long> consumer = new EventJournalConsumer<>(resultMap, partitionCount);

        long begin = System.currentTimeMillis();
        while (System.currentTimeMillis() - begin < durationInMillis) {
            boolean isEmpty = consumer.drain(e -> {
                assertEquals("EXACTLY_ONCE -> Unexpected count for " + e.getKey(),
                        countPerTicker, (long) e.getNewValue());
                queueVerifier.offer(e.getKey());
            });
            if (isEmpty) {
                SECONDS.sleep(1);
            }
        }
        System.out.println("Cancelling jobs..");
        queueVerifier.close();
        job.cancel();
        while (job.getStatus() != COMPLETED) {
            SECONDS.sleep(1);
        }
    }

    @After
    public void teardown() throws Exception {
        tradeProducer.close();
        jet.shutdown();
    }

    private DAG testDAG() {
        WindowDefinition windowDef = slidingWindowDef(windowSize, slideBy);
        AggregateOperation1<Object, LongAccumulator, Long> counting = AggregateOperations.counting();
        DAG dag = new DAG();
        WatermarkGenerationParams<Long> wmGenParams = wmGenParams((Long t) -> t, withFixedLag(lagMs),
                emitByFrame(windowDef), 10_000);

        Vertex journal = dag.newVertex("journal", streamMapP(mapName, e -> e.getType() == ADDED || e.getType() == UPDATED,
                EventJournalMapEvent<Long, Long>::getNewValue, START_FROM_OLDEST, wmGenParams));
        Vertex accumulateByF = dag.newVertex("accumulate-by-frame", accumulateByFrameP(
                wholeItem(), (Long t) -> t, TimestampKind.EVENT, windowDef, counting)
        );
        Vertex slidingW = dag.newVertex("sliding-window", combineToSlidingWindowP(windowDef, counting));
        Vertex writeMap = dag.newVertex("writeMap", SinkProcessors.writeMapP(resultsMapName));

        dag
                .edge(between(journal, accumulateByF).partitioned(wholeItem(), HASH_CODE))
                .edge(between(accumulateByF, slidingW).partitioned(entryKey())
                                                      .distributed())
                .edge(between(slidingW, writeMap));
        return dag;
    }

    private void deployResources() {
        HazelcastClientInstanceImpl instance = (HazelcastClientInstanceImpl) jet.getHazelcastInstance();
        GroupConfig gc = instance.getClientConfig().getGroupConfig();


        ClientConfig clientConfig = new ClientConfig();
        GroupConfig groupConfig = clientConfig.getGroupConfig();
        groupConfig.setName(gc.getName()).setPassword(gc.getPassword());
        clientConfig.setNetworkConfig(instance.getClientConfig().getNetworkConfig());
        ClientUserCodeDeploymentConfig userCodeDeploymentConfig = clientConfig.getUserCodeDeploymentConfig();
        userCodeDeploymentConfig.setEnabled(true);
        userCodeDeploymentConfig.addClass(EventJournalTest.class);
        userCodeDeploymentConfig.addClass(EventJournalTradeProducer.class);
        userCodeDeploymentConfig.addClass(EventJournalConsumer.class);
        userCodeDeploymentConfig.addClass(QueueVerifier.class);
        JetInstance client = Jet.newJetClient(clientConfig);
        client.shutdown();
    }

    private void addMembershipListenerForRestart(Job job) {
        jet.getHazelcastInstance().getCluster().addMembershipListener(new MembershipAdapter() {
            @Override
            public void memberAdded(MembershipEvent membershipEvent) {
                job.restart();
            }
        });
    }

}