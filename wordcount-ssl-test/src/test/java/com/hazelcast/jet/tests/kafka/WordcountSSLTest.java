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

package com.hazelcast.jet.tests.kafka;

import com.hazelcast.jet.JetInstance;
import com.hazelcast.jet.Traverser;
import com.hazelcast.jet.Util;
import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.jet.core.Vertex;
import com.hazelcast.jet.core.processor.SinkProcessors;
import com.hazelcast.jet.core.processor.SourceProcessors;
import com.hazelcast.jet.function.DistributedFunction;
import com.hazelcast.jet.server.JetBootstrap;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Address;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import static com.hazelcast.jet.Util.entry;
import static com.hazelcast.jet.aggregate.AggregateOperations.counting;
import static com.hazelcast.jet.aggregate.AggregateOperations.summingLong;
import static com.hazelcast.jet.core.Edge.between;
import static com.hazelcast.jet.core.Partitioner.HASH_CODE;
import static com.hazelcast.jet.core.processor.Processors.aggregateByKeyP;
import static com.hazelcast.jet.core.processor.Processors.flatMapP;
import static com.hazelcast.jet.core.processor.Processors.noopP;
import static com.hazelcast.jet.function.DistributedFunctions.entryKey;
import static com.hazelcast.jet.function.DistributedFunctions.wholeItem;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(JUnit4.class)
public class WordcountSSLTest {

    private static int count;
    private static int distinct;
    private static int words_per_row;

    private int warmupTime;
    private int totalTime;


    private JetInstance jet;
    private ILogger logger;

    public static void main(String[] args) {
        JUnitCore.main(WordcountSSLTest.class.getName());
    }

    @Before
    public void setUp() {
        count = Integer.parseInt(System.getProperty("count", "1000000"));
        distinct = Integer.parseInt(System.getProperty("distinct", "100000"));
        words_per_row = Integer.parseInt(System.getProperty("wordsPerRow", "20"));
        warmupTime = Integer.parseInt(System.getProperty("warmupTime", "20000"));
        totalTime = Integer.parseInt(System.getProperty("totalTime", "60000"));
        jet = JetBootstrap.getInstance();
        logger = jet.getHazelcastInstance().getLoggingService().getLogger(WordcountSSLTest.class);
        generateMockInput();
    }

    @After
    public void tearDown() {
        jet.shutdown();
    }

    private void generateMockInput() {
        logger.info("Generating input");
        final DAG dag = new DAG();
        Vertex source = dag.newVertex("source",
                (List<Address> addrs) -> (Address addr) -> ProcessorSupplier.of(
                        addr.equals(addrs.get(0)) ? MockInputP::new : noopP()));
        Vertex sink = dag.newVertex("sink", SinkProcessors.writeMapP("words"));
        dag.edge(between(source.localParallelism(1), sink.localParallelism(1)));
        jet.newJob(dag).join();
        logger.info("Input generated.");
    }

    private static class MockInputP extends AbstractProcessor {
        private int row;
        private int counter;
        private final StringBuilder sb = new StringBuilder();

        private final Traverser<Map.Entry<Integer, String>> trav = () -> {
            if (counter == count) {
                return null;
            }
            sb.setLength(0);
            String delimiter = "";
            for (int i = 0; i < words_per_row && counter < count; i++, counter++) {
                sb.append(delimiter).append(counter % distinct);
                delimiter = " ";
            }
            return entry(row++, sb.toString());
        };

        @Override
        public boolean complete() {
            return emitFromTraverser(trav);
        }
    }


    @Test
    public void testJet() {
        DAG dag = new DAG();

        Vertex source = dag.newVertex("source", SourceProcessors.readMapP("words"));
        Vertex tokenize = dag.newVertex("tokenize",
                flatMapP((Map.Entry<?, String> line) -> {
                    StringTokenizer s = new StringTokenizer(line.getValue());
                    return () -> s.hasMoreTokens() ? s.nextToken() : null;
                })
        );
        // word -> (word, count)
        Vertex aggregateStage1 = dag.newVertex("aggregateStage1",
                aggregateByKeyP(singletonList(wholeItem()), counting(), Util::entry));
        // (word, count) -> (word, count)
        DistributedFunction<Map.Entry, ?> getEntryKeyFn = Map.Entry::getKey;
        Vertex aggregateStage2 = dag.newVertex("aggregateStage2",
                aggregateByKeyP(
                        singletonList(getEntryKeyFn),
                        summingLong(Map.Entry<String, Long>::getValue),
                        Util::entry
                )
        );
        Vertex sink = dag.newVertex("sink", SinkProcessors.writeMapP("counts"));

        dag.edge(between(source.localParallelism(1), tokenize))
                .edge(between(tokenize, aggregateStage1)
                        .partitioned(wholeItem(), HASH_CODE))
                .edge(between(aggregateStage1, aggregateStage2)
                        .distributed()
                        .partitioned(entryKey()))
                .edge(between(aggregateStage2, sink.localParallelism(1)));

        benchmark("jet", () -> jet.newJob(dag).join());
        assertCounts(jet.getMap("counts"));
    }

    private static void assertCounts(Map<String, Long> wordCounts) {
        for (int i = 0; i < distinct; i++) {
            Long count = wordCounts.get(Integer.toString(i));
            assertNotNull("Missing count for " + i, count);
            assertEquals("The count for " + i + " is not correct", count / distinct, (long) count);
        }
    }

    private void benchmark(String label, Runnable run) {
        List<Long> times = new ArrayList<>();
        long testStart = System.currentTimeMillis();
        int warmupCount = 0;
        boolean warmupEnded = false;
        logger.info("Starting test..");
        logger.info("Warming up...");
        while (true) {
            System.gc();
            System.gc();
            long start = System.currentTimeMillis();
            run.run();
            long end = System.currentTimeMillis();
            long time = end - start;
            times.add(time);
            logger.info(label + ": totalTime=" + time);
            long sinceTestStart = end - testStart;
            if (sinceTestStart < warmupTime) {
                warmupCount++;
            }

            if (!warmupEnded && sinceTestStart > warmupTime) {
                logger.info("Warm up ended");
                warmupEnded = true;
            }

            if (sinceTestStart > totalTime) {
                break;
            }
        }
        logger.info("Test complete");
        System.out.println(times.stream()
                .skip(warmupCount).mapToLong(l -> l).summaryStatistics());
    }

}
