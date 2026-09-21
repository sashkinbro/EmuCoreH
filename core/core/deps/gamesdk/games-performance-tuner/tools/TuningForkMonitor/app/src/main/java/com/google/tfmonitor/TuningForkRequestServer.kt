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

import android.net.Uri
import com.google.tuningfork.AppKey
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

fun String.HeaderTrim() =
    this.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[1].trim { it <= ' ' }

data class HeaderInfo(
    val contentLen: Int,
    val contentType: String,
    val connectionType: String,
    val hostname: String,
    val userAgent: String,
    val encoding: String,
    val requestLocation: String
)

class TuningForkRequestServer(ip: String, port: Int, val requestListener: RequestListener) :
    Thread() {
    private val serverSocket: ServerSocket
    private var isStart = true
    private var keepAlive = true
    private var serverName = "TuningForkRequestServer v1.0"

    init {
        val addr = InetAddress.getByName(ip)
        serverSocket = ServerSocket(port, 100, addr)
        serverSocket.setSoTimeout(5000)  //set timeout for listener
    }

    override fun run() {
        while (isStart) {
            try {
                //wait for new connection on port
                val newSocket = serverSocket.accept()
                val newClient = ResponseThread(newSocket, requestListener)
                newClient.start()
            } catch (s: SocketTimeoutException) {
            } catch (e: IOException) {
            }

        }
    }

    inner class ResponseThread(protected var socket: Socket, val requestListener: RequestListener) :
        Thread() {

        internal val TAG = "AppApis"

        private var requestType = "GET"
        private var httpVer = "HTTP/1.1"
        private var contentType = ""

        val kPageNotFound = 404
        val kOkay = 200

        val kContentTypePattern =
            Regex("([ |\t]*content-type[ |\t]*:)(.*)", RegexOption.IGNORE_CASE)
        val kContentLengthPattern = Regex("Content-Length:", RegexOption.IGNORE_CASE)
        val kUserAgentPattern = Regex("User-Agent:", RegexOption.IGNORE_CASE)
        val kClientHostPattern = Regex("Host:", RegexOption.IGNORE_CASE)
        val kConnectionTypePattern = Regex("Connection:", RegexOption.IGNORE_CASE)
        val kAcceptEncodingPattern = Regex("Accept-Encoding:", RegexOption.IGNORE_CASE)

        private fun parseHeaders(header: Array<String>): HeaderInfo {
            var contentLen = "0"
            var contentType = "text/html"
            var connectionType = "keep-alive"
            var hostname = ""
            var userAgent = ""
            var encoding = ""
            var requestLocation = ""

            val h1 =
                header[0].split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (h1.size == 3) {
                requestType = h1[0]
                requestLocation = h1[1]
                httpVer = h1[2]
            }

            for (h in header.indices) {
                val value = header[h].trim { it <= ' ' }

                if (kContentLengthPattern.containsMatchIn(value)) {
                    contentLen = value.HeaderTrim()
                }
                if (kContentTypePattern.containsMatchIn(value)) {
                    contentType = value.HeaderTrim()
                }
                if (kConnectionTypePattern.containsMatchIn(value)) {
                    connectionType = value.HeaderTrim();
                }
                if (kClientHostPattern.containsMatchIn(value)) {
                    hostname = value.HeaderTrim()
                }
                if (kAcceptEncodingPattern.containsMatchIn(value)) {
                    encoding = value.HeaderTrim()
                }
                if (kUserAgentPattern.containsMatchIn(value)) {
                    for (ua in value.split(":".toRegex())
                        .dropLastWhile { it.isEmpty() }.toTypedArray()) {
                        if (!ua.equals("User-Agent:", ignoreCase = true)) {
                            userAgent += ua.trim { it <= ' ' }
                        }
                    }
                }
            }

            return HeaderInfo(
                Integer.valueOf(contentLen), contentType, connectionType, hostname,
                userAgent, encoding, requestLocation
            )
        }

        private fun processPostRequest(
            header: HeaderInfo, lastHeaderLine: String,
            inp: DataInputStream?
        ) {
            var out = DataOutputStream(socket.getOutputStream())
            var postData = lastHeaderLine
            if (header.contentLen > 0) {
                val data = ByteArray(1500)
                var contentLength = header.contentLen
                // Read any extra data
                while (contentLength > postData.length) {
                    val read_size = inp!!.read(data)
                    if (read_size <= 0) break
                    val str = StringBuilder()
                    val recData = String(data).trim { it <= ' ' }
                    str.append(postData)
                    str.append(recData)
                    postData = str.toString()
                }
                if (contentLength < postData.length)
                    postData = postData.substring(0, contentLength)
            }

            if (!header.requestLocation.isEmpty()) {
                processLocation(out, header.requestLocation, postData)
            }
        }

        override fun run() {
            try {
                var inp: DataInputStream? = null

                if (socket.isConnected) {
                    inp = DataInputStream(socket.getInputStream())
                }

                val data = ByteArray(1500)
                socket.setSoTimeout(60 * 1000 * 5);

                while (true) {
                    val read_size = inp!!.read(data)
                    if (read_size == -1) break
                    var recData = String(data).trim { it <= ' ' }
                    val headers = recData.split("\\r?\\n".toRegex()).dropLastWhile { it.isEmpty() }
                        .toTypedArray()
                    if (!headers.isEmpty()) {
                        val header = parseHeaders(headers)
                        if (requestType.equals("POST", ignoreCase = true))
                            processPostRequest(header, headers.last(), inp)
                    }
                }
            } catch (er: SocketException) {

            } catch (er: Exception) {
                er.printStackTrace()
            }
        }

        fun processLocation(out: DataOutputStream?, location: String, postData: String) {

            println("url location -> $location")

            val geturi = Uri.parse("http://localhost$location")
            val dirPath =
                geturi.path?.split("/".toRegex())?.dropLastWhile({ it.isEmpty() })?.toTypedArray()
                    ?: arrayOf()
            if (dirPath.size > 1) {
                val fileName = dirPath[dirPath.size - 1]
                var qparms = mutableMapOf<String, String?>()
                if (requestType == "POST") {
                    qparms.set("_POST", postData)
                }
                val (status, data) = getResultByName(
                    dirPath.dropLast(1).joinToString("/"),
                    fileName,
                    qparms
                )
                if (out != null)
                    constructHeader(out, data.length.toString() + "", status, data)
            }
        }

        fun getResultByName(
            path: String,
            name: String,
            qparms: MutableMap<String, String?>
        ): Pair<Int, String> {
            var path = path
            val name_parts = name.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            var function = name
            if (name_parts.size > 1) {
                function = name_parts[name_parts.size - 1]
                path += "/" + name_parts.dropLast(1).joinToString(":")
                qparms.set("PATH", path)
            }
            try {
                val app = AppKey.parse(path)
                val postData = qparms["_POST"]
                val result = if (postData != null) {
                    when (function) {
                        "generateTuningParameters" -> requestListener.generateTuningParameters(
                            app,
                            postData
                        )
                        "uploadTelemetry" -> requestListener.uploadTelemetry(app, postData)
                        "debugInfo" -> requestListener.debugInfo(app, postData)
                        else -> errorResult()
                    }
                } else errorResult()
                return result
            } catch (er: Exception) {
                er.printStackTrace()
                return errorResult()
            }
        }

        fun errorResult(): Pair<Int, String> {
            return Pair(kPageNotFound, ("<!DOCTYPE html>"
                    + "<html><head><title>Page not found</title>"
                    + "</head><body><h3>Requested page not found</h3></body></html>"))
        }

        private fun constructHeader(output: DataOutputStream, size: String, status: Int,
                                    data: String) {
            val gmtFrmt = SimpleDateFormat("E, d MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            gmtFrmt.timeZone = TimeZone.getTimeZone("GMT")
            val pw = PrintWriter(BufferedWriter(OutputStreamWriter(output)), false)
            pw.append("HTTP/1.1 ").append(status.toString()).append(" \r\n")
            if (contentType != null) {
                printHeader(pw, "Content-Type", contentType)
            }
            printHeader(pw, "Date", gmtFrmt.format(Date()))
            printHeader(pw, "Connection", if (keepAlive) "keep-alive" else "close")
            printHeader(pw, "Content-Length", size)
            printHeader(pw, "Server", serverName)
            pw.append("\r\n")
            pw.append(data)
            pw.flush()
            pw.close();
        }

        private fun printHeader(pw: PrintWriter, key: String, value: String) {
            pw.append(key).append(": ").append(value).append("\r\n")
        }

    }

    fun close() {
        if (isStart) {
            try {
                isStart = false
                println("Server stopped running !")
            } catch (er: IOException) {
                er.printStackTrace()
            }

        }
    }

    companion object {

        var isStart = false
        private var server: TuningForkRequestServer? = null

        fun startServer(ip: String, port: Int, requestListener: RequestListener) {
            try {

                if (!isStart) {
                    server = TuningForkRequestServer(ip, port, requestListener)
                    val eh =
                        UncaughtExceptionHandler { server, e -> println("Uncaught exception!") }
                    server?.uncaughtExceptionHandler = eh
                    server?.start()
                    println("Server Started on port $port!")
                    isStart = true
                }
            } catch (e: IOException) {
                e.printStackTrace()
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }

        fun stopServer() {
            server?.close()
        }

    }

}