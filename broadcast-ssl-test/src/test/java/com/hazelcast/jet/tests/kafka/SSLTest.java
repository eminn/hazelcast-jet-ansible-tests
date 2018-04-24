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
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.Edge;
import com.hazelcast.jet.core.Vertex;
import com.hazelcast.jet.core.processor.Processors;
import com.hazelcast.jet.core.processor.SinkProcessors;
import com.hazelcast.jet.core.processor.SourceProcessors;
import com.hazelcast.jet.server.JetBootstrap;
import com.hazelcast.logging.ILogger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import tests.kafka.TradeProducer;

import java.util.ArrayList;
import java.util.List;

@RunWith(JUnit4.class)
public class SSLTest {

    private int warmupTime;
    private int totalTime;

    private String inputList;
    private String outputList;
    private int tickerCount;
    private int countPerTicker;
    private JetInstance jet;
    private ILogger logger;

    public static void main(String[] args) {
        JUnitCore.main(SSLTest.class.getName());
    }

    @Before
    public void setUp() {
        inputList = System.getProperty("inputList", String.format("%s-%d", "trades", System.currentTimeMillis()));
        outputList = System.getProperty("outputList", "jet-output-" + System.currentTimeMillis());
        tickerCount = Integer.parseInt(System.getProperty("tickerCount", "100"));
        warmupTime = Integer.parseInt(System.getProperty("warmupTime", "20000"));
        totalTime = Integer.parseInt(System.getProperty("totalTime", "60000"));
        countPerTicker = Integer.parseInt(System.getProperty("countPerTicker", "10"));
        jet = JetBootstrap.getInstance();
        logger = jet.getHazelcastInstance().getLoggingService().getLogger(SSLTest.class);

        TradeProducer tradeProducer = new TradeProducer(jet);
        tradeProducer.produce(inputList, tickerCount, countPerTicker);
    }

    @Test
    public void sslBenchmark() {
        benchmark("jet", () -> {
            jet.newJob(dag()).join();
            jet.getList(outputList).clear();

        });
    }

    @After
    public void tearDown() {
        jet.getList(inputList).clear();
        jet.shutdown();
    }

    private DAG dag() {
        DAG dag = new DAG();
        Vertex source = dag.newVertex("hazelcast-list-source", SourceProcessors.readListP(inputList));
        Vertex sink = dag.newVertex("hazelcast-list-sink", SinkProcessors.writeListP(outputList));
        int mapperCountPerLevel = 5;
        ArrayList<Vertex> firstLevelMappers = new ArrayList<>();
        for (int i = 0; i < mapperCountPerLevel; i++) {
            Vertex firstLevelMapper = dag.newVertex("firstLevelMapper-" + i, Processors.mapP(t -> t));
            firstLevelMappers.add(firstLevelMapper);
            dag.edge(Edge.from(source, i).to(firstLevelMapper).distributed().broadcast());
        }
        for (int i = 0; i < mapperCountPerLevel; i++) {
            Vertex secondLevelMapper = dag.newVertex("secondLevelMapper-" + i, Processors.mapP(t -> t));
            int j = 0;
            for (Vertex mapper : firstLevelMappers) {
                dag.edge(Edge.from(mapper, i).to(secondLevelMapper, j++).distributed().broadcast());
            }
            dag.edge(Edge.from(secondLevelMapper, 0).to(sink, i).distributed().broadcast());
        }
        return dag;
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
