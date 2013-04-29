/*
 * Copyright (c) 2011 2linessoftware.com
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
package com.twolinessoftware.android;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import android.util.Log;

public class SendLocationWorkerQueue {
    private final String LOGTAG = "Worker Queue";
    private static final int SAMPLES_IN_MINUTES = 60;
    private List<SendLocationWorker> queue;
    private boolean running;
    private final WorkerThread thread;

    private final Object lock = new Object();
    private Integer workerIndex = 0;

    public SendLocationWorkerQueue() {
        queue = new ArrayList<SendLocationWorker>();
        thread = new WorkerThread();
        running = false;
    }

    public void addToQueue(SendLocationWorker worker) {
        synchronized (queue) {
            queue.add(worker);
        }

    }

    public synchronized void start() {
        running = true;
        thread.start();
    }

    public synchronized void stop() {
        /*
         * synchronized(lock){ lock.notify(); }
         */
        running = false;
    }

    public void reset() {
        stop();
        queue = new LinkedList<SendLocationWorker>();
    }

    private class WorkerThread extends Thread {

        @Override
        public void run() {
            while (running) {

                if (!queue.isEmpty()) {
                    long timeUntilNext = queue.get(workerIndex).getSendTime() - System.currentTimeMillis();

                    if (timeUntilNext < 10) {
                        try {
                            SendLocationWorker worker = queue.get(workerIndex);
                            worker.run();
                            jump(1);
                            Log.i(LOGTAG, "Currently @" + workerIndex);
                        } catch (Exception e) {
                            Log.e(LOGTAG, "workerIndex: " + e.getMessage());
                        }
                    } else {
                        synchronized (lock) {
                            try {
                                lock.wait(timeUntilNext);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        }
    }

    public void jump(int i) {
        synchronized (workerIndex) {
            workerIndex = (workerIndex + (SendLocationWorkerQueue.SAMPLES_IN_MINUTES * i)) % queue.size();
        }
    }
}
