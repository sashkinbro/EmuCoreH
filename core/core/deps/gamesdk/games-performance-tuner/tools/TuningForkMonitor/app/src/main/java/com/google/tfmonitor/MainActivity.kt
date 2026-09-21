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

package com.google.tfmonitor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.findNavController

import com.google.tfmonitor.databinding.ActivityMainBinding

import com.google.tuningfork.AppKey
import com.google.tuningfork.Datastore
import com.google.tuningfork.Deserializer
import java.io.File

private class AsyncWebServer(
    val applicationContext: Context, val port: Int,
    val requestListener: RequestListener
) :
    AsyncTask<Unit, Unit, Unit>() {

    private var idir: File? = null;

    override fun onPreExecute() {
        super.onPreExecute()
        var tempDir = applicationContext.cacheDir;

        idir = File(tempDir.absolutePath + "/TuningForkMonitor/public_html");
        idir?.mkdirs();

        var ifile = File(idir?.absolutePath + "/index.html");
        ifile.writeText("<html><body>The TuningForkMonitor web server is running.</body></html>");

    }

    override fun doInBackground(vararg params: Unit?) {
        TuningForkRequestServer.startServer("localhost", port, requestListener)
    }
}

class MainActivity : AppCompatActivity(), AppChoiceFragment.OnFragmentInteractionListener,
                     AppDetailFragment.OnFragmentInteractionListener,
                     FidelityParametersFragment.OnFragmentInteractionListener,
                     AnnotationsFragment.OnFragmentInteractionListener, RequestListener {

    private lateinit var databaseName: String

    private var navController: NavController? = null
    private lateinit var datastore: Datastore

    private fun getViewModel(): MonitorViewModel {
        val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        return ViewModelProvider(viewModelStore, factory).get(MonitorViewModel::class.java)
    }

    override fun generateTuningParameters(
        appKey: AppKey,
        requestString: String
    ): Pair<Int, String> {
        val deser = Deserializer()
        val req = deser.parseGenerateTuningParametersRequest(requestString)
        return getViewModel().generateTuningParameters(appKey, req)
    }

    override fun uploadTelemetry(appKey: AppKey, requestString: String): Pair<Int, String> {
        val deser = Deserializer()
        val req = deser.parseUploadTelemetryRequest(requestString)
        datastore.uploadTelemetryRequest(appKey, req)
        return getViewModel().uploadTelemetry(appKey, req)
    }

    override fun debugInfo(appKey: AppKey, requestString: String): Pair<Int, String> {
        val deser = Deserializer()
        val req = deser.parseDebugInfoRequest(requestString)
        datastore.debugInfoRequest(appKey, req)
        return getViewModel().debugInfo(appKey, req)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater);
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        databaseName = getString(R.string.database_name)
        datastore = Datastore(this.applicationContext, databaseName)

        loadStoredApps()

        navController = findNavController(R.id.nav_controller_view_tag)

        AsyncWebServer(applicationContext, 9000, this).execute();

    }

    override fun onDestroy() {
        super.onDestroy()
        TuningForkRequestServer.stopServer();
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    fun getDatabaseURI(c: Context, dbname: String): Uri {
        val exportFile = c.getDatabasePath(dbname)
        return FileProvider.getUriForFile(c, "com.google.tfmonitor.fileprovider", exportFile)

    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.

        return when (item.itemId) {
            R.id.action_show_log -> {
                if (navController?.currentDestination?.id == R.id.log_fragment) {
                    navController?.popBackStack()
                } else {
                    navController?.navigate(R.id.log_fragment)
                }
                true
            }
            R.id.action_export -> {
                exportDatabase()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onFragmentInteraction(uri: Uri): Unit {
        System.out.println(uri.toString())
    }

    fun loadStoredApps() {
        val apps = datastore.getAllKnownApps()
        for (a in apps) {
            getViewModel().addAppMaybe(a, MonitorViewModel.AppType.STORED)
        }
    }

    fun exportDatabase() {
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "application/octet-stream"
        val uri: Uri = getDatabaseURI(this, databaseName)
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        val chooser = Intent.createChooser(intent, "Export to:")
        // Without these permissions, there are errors in the log, although the export chooser
        // still seems to work.
        val uriPermissions =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        intent.addFlags(uriPermissions)
        val resInfoList =
            packageManager.queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)
        for (resolveInfo in resInfoList) {
            val packageName = resolveInfo.activityInfo.packageName
            grantUriPermission(packageName, uri, uriPermissions)
        }
        startActivity(chooser)
    }
}
