/* Copyright 2013 RobustNet Lab, University of Michigan. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mobilyzer;

import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Callable;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.myjson.JsonArray;
import com.google.myjson.JsonObject;
import com.mobilyzer.MeasurementResult.TaskProgress;
import com.mobilyzer.exceptions.MeasurementError;
import com.mobilyzer.util.Logger;
import com.mobilyzer.util.MeasurementJsonConvertor;
import com.mobilyzer.util.PhoneUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class UserMeasurementTask implements Callable<MeasurementResult[]> {
    private MeasurementTask realTask;
    private MeasurementScheduler scheduler;
    private ContextCollector contextCollector;

    public UserMeasurementTask(MeasurementTask task,
                               MeasurementScheduler scheduler) {
        realTask = task;
        this.scheduler = scheduler;
        this.contextCollector = new ContextCollector();
    }

    /**
     * Notify the scheduler that this task is started
     */
    private void broadcastMeasurementStart() {
        Intent intent = new Intent();
        intent.setAction(UpdateIntent.MEASUREMENT_PROGRESS_UPDATE_ACTION);
        intent.putExtra(UpdateIntent.TASK_PRIORITY_PAYLOAD,
                MeasurementTask.USER_PRIORITY);
        intent.putExtra(UpdateIntent.TASK_STATUS_PAYLOAD, Config.TASK_STARTED);
        intent.putExtra(UpdateIntent.TASKID_PAYLOAD, realTask.getTaskId());
        intent.putExtra(UpdateIntent.CLIENTKEY_PAYLOAD, realTask.getKey());
        scheduler.sendBroadcast(intent);
    }

    /**
     * Notify the scheduler that this task is finished executing.
     * The result can be completed, paused or failed due to exception
     *
     * @param results Results of the task
     */
    private void broadcastMeasurementEnd(MeasurementResult[] results) {
        Intent intent = new Intent();
        intent.setAction(UpdateIntent.MEASUREMENT_PROGRESS_UPDATE_ACTION);
        //TODO fixed one value priority for all users task?
        intent.putExtra(UpdateIntent.TASK_PRIORITY_PAYLOAD,
                MeasurementTask.USER_PRIORITY);
        intent.putExtra(UpdateIntent.TASKID_PAYLOAD, realTask.getTaskId());
        intent.putExtra(UpdateIntent.CLIENTKEY_PAYLOAD, realTask.getKey());

        if (results != null) {
            //TODO only single task can be paused
            if (results[0].getTaskProgress() == TaskProgress.PAUSED) {
                intent.putExtra(UpdateIntent.TASK_STATUS_PAYLOAD, Config.TASK_PAUSED);
            } else {
                intent.putExtra(UpdateIntent.TASK_STATUS_PAYLOAD, Config.TASK_FINISHED);
                intent.putExtra(UpdateIntent.RESULT_PAYLOAD, results);
            }
            scheduler.sendBroadcast(intent);
        }

    }

    /**
     * The call() method that broadcast intents before the measurement starts
     * and after the measurement finishes.
     */
    @Override
    public MeasurementResult[] call() throws MeasurementError {
        MeasurementResult[] results = null;
        PhoneUtils phoneUtils = PhoneUtils.getPhoneUtils();
        try {
            phoneUtils.acquireWakeLock();
            broadcastMeasurementStart();
            contextCollector.setInterval(realTask.getDescription().contextIntervalSec);
            contextCollector.startCollector();
            results = realTask.call();
            ArrayList<HashMap<String, String>> contextResults =
                    contextCollector.stopCollector();
            for (MeasurementResult r : results) {
                r.addContextResults(contextResults);
                r.getDeviceProperty().dnResolvability = contextCollector.dnsConnectivity;
                r.getDeviceProperty().ipConnectivity = contextCollector.ipConnectivity;

                //Logger.d("dns test isSens: " + r.isSensitive);
            }
        } catch (MeasurementError e) {
            Logger.e("User measurement " + realTask.getDescriptor() + " has failed");
            Logger.e(e.getMessage());
            results = MeasurementResult.getFailureResult(realTask, e);
        } catch (Exception e) {
            Logger.e("User measurement " + realTask.getDescriptor() + " has failed");
            Logger.e("Unexpected Exception: " + e.getMessage());
            results = MeasurementResult.getFailureResult(realTask, e);
        } finally {
            broadcastMeasurementEnd(results);
            MeasurementTask currentTask = scheduler.getCurrentTask();
            if (currentTask != null && currentTask.equals(realTask)) {
                scheduler.setCurrentTask(null);
            }
            phoneUtils.releaseWakeLock();
        }


        JSONArray resultArray = new JSONArray();
        for (MeasurementResult result : results) {
            try {
                JSONObject item = MeasurementJsonConvertor.encodeToJson(result);
                //Logger.d("dns test: result isSensJs " + result.isSensitive + " item " + item.get("is_sensitive"));
                resultArray.put(item);
            } catch (JSONException e) {
                Log.e("Testing", "Problem encoding results");
            }
        }
        String stringy = resultArray.toString();

        String filename = "test-results.json";
        FileOutputStream outputStream;

        try {
            outputStream = scheduler.context.openFileOutput(filename, Context.MODE_PRIVATE);
            outputStream.write(stringy.getBytes());
            outputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        //Logger.d("dns test: received measurement: " + stringy);
        return results;
    }


}
