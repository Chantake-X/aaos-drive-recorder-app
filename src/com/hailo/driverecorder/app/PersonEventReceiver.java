package com.hailo.driverecorder.app;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.hailo.driverecorder.IDriveRecorderService;
import com.hailo.driverecorder.PersonEvent;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class PersonEventReceiver extends BroadcastReceiver {
    private static final String TAG = "DriveRecorderApp";
    private static final String SERVICE_NAME = "hailo.driverecorder";
    private static final int QUEUE_CAPACITY = 13;
    private static final int SCHEMA_VERSION = 1;
    private static final int STATE_START = 1;
    private static final int STATE_END = 2;

    private static final String ACTION_PERSON_EVENT =
            "com.hailo.driverecorder.app.action.PERSON_EVENT";
    private static final String EXTRA_SCHEMA_VERSION =
            "com.hailo.driverecorder.app.extra.SCHEMA_VERSION";
    private static final String EXTRA_EVENT_ID =
            "com.hailo.driverecorder.app.extra.EVENT_ID";
    private static final String EXTRA_STATE =
            "com.hailo.driverecorder.app.extra.STATE";
    private static final String EXTRA_MONOTONIC_TIMESTAMP_NS =
            "com.hailo.driverecorder.app.extra.MONOTONIC_TIMESTAMP_NS";

    private static final ComponentName EXPECTED_COMPONENT = new ComponentName(
            "com.hailo.driverecorder.app",
            "com.hailo.driverecorder.app.PersonEventReceiver");

    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "PersonEventReceiver");
                thread.setDaemon(false);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    @Override
    public void onReceive(Context context, Intent intent) {
        PersonEvent event = parseEvent(intent);
        if (event == null) {
            Log.w(TAG, "AIP4_RECEIVER_REJECT reason=invalid_intent");
            return;
        }

        PendingResult pendingResult = goAsync();
        try {
            EXECUTOR.execute(() -> forwardEvent(event, pendingResult));
            Log.i(TAG, "AIP4_RECEIVER_ENQUEUED id=" + event.eventId
                    + " state=" + event.state
                    + " queue_size=" + EXECUTOR.getQueue().size());
        } catch (RejectedExecutionException e) {
            Log.e(TAG, "AIP4_RECEIVER_DROP reason=queue_full id=" + event.eventId
                    + " state=" + event.state
                    + " capacity=" + QUEUE_CAPACITY, e);
            pendingResult.finish();
        }
    }

    private static PersonEvent parseEvent(Intent intent) {
        if (intent == null
                || !ACTION_PERSON_EVENT.equals(intent.getAction())
                || !EXPECTED_COMPONENT.equals(intent.getComponent())) {
            return null;
        }

        Bundle extras = intent.getExtras();
        if (extras == null
                || !(extras.get(EXTRA_SCHEMA_VERSION) instanceof Integer)
                || !(extras.get(EXTRA_EVENT_ID) instanceof String)
                || !(extras.get(EXTRA_STATE) instanceof Integer)
                || !(extras.get(EXTRA_MONOTONIC_TIMESTAMP_NS) instanceof Long)) {
            return null;
        }

        int schemaVersion = extras.getInt(EXTRA_SCHEMA_VERSION);
        String eventId = extras.getString(EXTRA_EVENT_ID);
        int state = extras.getInt(EXTRA_STATE);
        long timestampNs = extras.getLong(EXTRA_MONOTONIC_TIMESTAMP_NS);
        if (schemaVersion != SCHEMA_VERSION
                || eventId == null
                || eventId.isEmpty()
                || (state != STATE_START && state != STATE_END)
                || timestampNs <= 0L) {
            return null;
        }
        try {
            UUID.fromString(eventId);
        } catch (IllegalArgumentException e) {
            return null;
        }

        PersonEvent event = new PersonEvent();
        event.schemaVersion = schemaVersion;
        event.eventId = eventId;
        event.state = state;
        event.monotonicTimestampNs = timestampNs;
        return event;
    }

    private static void forwardEvent(PersonEvent event, PendingResult pendingResult) {
        try {
            IBinder binder = android.os.ServiceManager.getService(SERVICE_NAME);
            if (binder == null) {
                Log.e(TAG, "AIP4_RECEIVER_DROP reason=system_service_unavailable"
                        + " id=" + event.eventId + " state=" + event.state);
                return;
            }
            IDriveRecorderService service = IDriveRecorderService.Stub.asInterface(binder);
            boolean accepted = service.submitPersonEvent(event);
            Log.i(TAG, "AIP4_RECEIVER_FORWARDED id=" + event.eventId
                    + " state=" + event.state + " accepted=" + accepted);
        } catch (Exception e) {
            Log.e(TAG, "AIP4_RECEIVER_DROP reason=binder_exception"
                    + " id=" + event.eventId + " state=" + event.state, e);
        } finally {
            pendingResult.finish();
            Log.i(TAG, "AIP4_RECEIVER_FINISHED id=" + event.eventId
                    + " state=" + event.state);
        }
    }
}
