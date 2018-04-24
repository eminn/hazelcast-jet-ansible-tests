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

package tests.kafka;

import com.hazelcast.jet.IListJet;
import com.hazelcast.jet.JetInstance;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class TradeProducer {

    private static final int INITIAL_PRICE = 10000;
    private static final int QUANTITY = 100;

    private Map<String, Integer> tickersToPrice = new HashMap<>();
    private JetInstance jetInstance;

    public TradeProducer(JetInstance jetInstance) {
        this.jetInstance = jetInstance;
        loadTickers();
    }


    public void produce(String listName, int tickerCount, int countPerTicker) {
        final long[] timeStamp = {0};
        IListJet<Trade> list = jetInstance.getList(listName);
        tickersToPrice.keySet().stream().limit(tickerCount).forEach(ticker -> {
            for (int i = 0; i < countPerTicker; i++) {
                Trade trade = new Trade(timeStamp[0], ticker, QUANTITY, INITIAL_PRICE);
                list.add(trade);
            }
            timeStamp[0]++;
        });
    }

    private void loadTickers() {
        InputStream tickers = TradeProducer.class.getResourceAsStream("/nasdaqlisted.txt");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(tickers))) {
            reader.lines().skip(1).map(l -> l.split("\\|")[0]).forEach(t -> tickersToPrice.put(t, INITIAL_PRICE));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
