/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.tuningfork

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.ArrayList
import java.util.Date
import org.json.JSONArray

class Datastore : SQLiteOpenHelper {
    companion object {
        val DATABASE_VERSION = 7

        val HIST_TABLE_NAME = "histograms"
        val HIST_COLUMN_ID = "_id"
        val HIST_COLUMN_TIME = "time"
        val HIST_COLUMN_INSTRUMENT_KEY = "ikey"
        val HIST_COLUMN_ANNOTATION = "annotation"
        val HIST_COLUMN_FIDELITY_PARAMS = "fps"
        val HIST_COLUMN_COUNTS = "counts"
        val HIST_INDEX_TIME = "idx_hist_time"

        val APP_TABLE_NAME = "apps"
        val APP_COLUMN_ID = "_id"
        val APP_COLUMN_NAME = "name"
        val APP_COLUMN_VERSION = "version"
        val APP_COLUMN_TIME = "time"
        val APP_COLUMN_DESCRIPTOR = "fps"
        val APP_COLUMN_SETTINGS = "settings"
        val APP_INDEX_TIME = "idx_app_time"

        fun MakeJsonArray(ia: IntArray): String {
            var aa = JSONArray(ia.toList())
            return aa.toString()
        }

    }

    constructor(context: Context, dbname: String) : super(context, dbname, null, DATABASE_VERSION) {
    }

    override fun onCreate(db: SQLiteDatabase?) {
        db?.execSQL(
            "CREATE TABLE " + HIST_TABLE_NAME + " (" +
                HIST_COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                HIST_COLUMN_TIME + " INTEGER, " +
                HIST_COLUMN_INSTRUMENT_KEY + " INTEGER, " +
                HIST_COLUMN_ANNOTATION + " BLOB," +
                HIST_COLUMN_FIDELITY_PARAMS + " BLOB," +
                HIST_COLUMN_COUNTS + " TEXT"
                + ")"
        )
        db?.execSQL(
            "CREATE TABLE " + APP_TABLE_NAME + " (" +
                APP_COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                APP_COLUMN_TIME + " INTEGER, " +
                APP_COLUMN_NAME + " TEXT, " +
                APP_COLUMN_VERSION + " INTEGER," +
                APP_COLUMN_DESCRIPTOR + " BLOB," +
                APP_COLUMN_SETTINGS + " BLOB"
                + ")"
        )
        db?.execSQL(
            "CREATE INDEX " + HIST_INDEX_TIME + " ON " + HIST_TABLE_NAME + " ("
                + HIST_COLUMN_TIME + ")"
        )
        db?.execSQL(
            "CREATE INDEX " + APP_INDEX_TIME + " ON " + APP_TABLE_NAME + " ("
                + APP_COLUMN_TIME + ")"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        db?.execSQL("DROP TABLE IF EXISTS " + HIST_TABLE_NAME);
        db?.execSQL("DROP TABLE IF EXISTS " + APP_TABLE_NAME);
        onCreate(db);
    }

    fun now() = java.util.Calendar.getInstance().time.time / 1000

    fun uploadTelemetryRequest(app: AppKey, request: UploadTelemetryRequest) {
        val database = writableDatabase
        try {
            for (r in request.telemetry) {
                r.report.rendering?.histograms?.let { histograms ->
                    for (h in histograms) {
                        val values = ContentValues()
                        values.put(HIST_COLUMN_TIME, now())
                        values.put(HIST_COLUMN_INSTRUMENT_KEY, h.instrument_id)
                        values.put(HIST_COLUMN_ANNOTATION, r.context.annotations)
                        values.put(
                            HIST_COLUMN_FIDELITY_PARAMS,
                            r.context.tuning_parameters.serialized_fidelity_parameters
                        )
                        values.put(HIST_COLUMN_COUNTS, MakeJsonArray(h.counts))
                        database.insert(HIST_TABLE_NAME, null, values)
                    }
                }
            }
        } finally {
            database.close()
        }
    }

    fun debugInfoRequest(app: AppKey, request: DebugInfoRequest) {
        val database = writableDatabase
        try {
            val values = ContentValues()
            values.put(APP_COLUMN_TIME, now())
            values.put(APP_COLUMN_NAME, app.name)
            values.put(APP_COLUMN_VERSION, app.version)
            values.put(APP_COLUMN_DESCRIPTOR, request.dev_tuningfork_descriptor)
            values.put(APP_COLUMN_SETTINGS, request.settings)
            database.insert(APP_TABLE_NAME, null, values)
        } finally {
            database.close()
        }
    }

    // processItem should return false if it wants to stop processing cursor items
    private fun makeRequest(request: String, processItem: (cursor: Cursor) -> Boolean) {
        val database = readableDatabase
        try {
            val cursor = database.rawQuery(request, null)
            cursor.moveToFirst()
            while (!cursor.isAfterLast) {
                if (!processItem(cursor))
                    break
                cursor.moveToNext()
            }
        } finally {
            database.close()
        }
    }

    private fun <T> makeArrayRequest(request: String, processItem: (cursor: Cursor) -> T)
        : ArrayList<T> {
        var result = ArrayList<T>()
        makeRequest(request, { cursor ->
            result.add(processItem(cursor))
            true // Get all items
        })
        return result
    }

    private fun <T> makeSingleItemRequest(request: String, processItem: (cursor: Cursor) -> T)
        : T? {
        var ret: T? = null
        makeRequest(request, { cursor ->
            ret = processItem(cursor)
            false // Finish after one item
        })
        return ret
    }

    fun getAllKnownApps(): List<AppKey> {
        val request =
            "SELECT DISTINCT " + APP_COLUMN_NAME + ", " + APP_COLUMN_VERSION + "  FROM " + APP_TABLE_NAME
        return makeArrayRequest(request, { cursor ->
            val name = cursor.getString(cursor.getColumnIndex(APP_COLUMN_NAME))
            val version = cursor.getInt(cursor.getColumnIndex(APP_COLUMN_VERSION))
            AppKey(name, version)
        })
    }

    fun getLatestSettingsForApp(app: AppKey): Tuningfork.Settings? {
        val request = "SELECT " + APP_COLUMN_SETTINGS + " FROM " + APP_TABLE_NAME +
            " WHERE " + APP_COLUMN_NAME + "='" + app.name + "' AND " + APP_COLUMN_VERSION +
            "=" + app.version.toString() + " ORDER BY " + APP_COLUMN_TIME + " DESC LIMIT 1"
        return makeSingleItemRequest(request, { cursor ->
            Tuningfork.Settings.parseFrom(
                cursor.getBlob(
                    cursor.getColumnIndex(
                        APP_COLUMN_SETTINGS
                    )
                )
            )
        })
    }

    fun getLatestHistogramForInstrumentKey(instrumentKey: Int): Pair<Date, RenderTimeHistogram>? {
        val request =
            "SELECT * FROM " + HIST_TABLE_NAME + " WHERE " + HIST_COLUMN_INSTRUMENT_KEY + "=" +
                instrumentKey.toString() + " ORDER BY " + HIST_COLUMN_TIME + " DESC LIMIT 1"
        return makeSingleItemRequest(request, { cursor ->
            val arr_json =
                JSONArray(cursor.getString(cursor.getColumnIndex(HIST_COLUMN_COUNTS)))
            val ls = IntArray(arr_json.length())
            for (i in 0 until arr_json.length()) {
                ls[i] = arr_json.getInt(i)
            }
            val time =
                Date(cursor.getInt(cursor.getColumnIndex(HIST_COLUMN_TIME)).toLong() * 1000)
            Pair(time, RenderTimeHistogram(instrumentKey, ls))
        })
    }

    fun getInstrumentKeysForApp(app: AppKey): IntArray {
        val request = "SELECT DISTINCT " + HIST_COLUMN_INSTRUMENT_KEY + " FROM " + HIST_TABLE_NAME
        val out = makeArrayRequest(request, { cursor ->
            val ikey = cursor.getInt(cursor.getColumnIndex(HIST_COLUMN_INSTRUMENT_KEY))
            ikey
        })
        return out.toIntArray()
    }

}
