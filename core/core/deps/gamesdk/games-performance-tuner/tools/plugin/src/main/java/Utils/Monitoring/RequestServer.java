/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * limitations under the License
 */

package Utils.Monitoring;

import com.google.android.performanceparameters.v1.PerformanceParameters.UploadTelemetryRequest;
import com.google.common.io.ByteStreams;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RequestServer {

  private static final ThreadPoolExecutor THREAD_POOL_EXECUTOR = (ThreadPoolExecutor) Executors
      .newFixedThreadPool(5);
  private final HttpServer localNetworkHttpServer, localHostHttpServer;
  private static boolean isBound = false;
  private final String EXPONENT_PATTERN = "\"duration\": \"[0-9]+\\.[0-9]+[eE][+-][0-9]+s\"";

  private Optional<Consumer<UploadTelemetryRequest>> monitoringAction = Optional.empty();
  private Optional<Supplier<ByteString>> fidelitySupplier = Optional.empty();
  private Optional<Consumer<JsonObject>> debugInfo = Optional.empty();
  private static RequestServer requestServer;

  private RequestServer() throws IOException {
    localNetworkHttpServer = HttpServer
        .create(new InetSocketAddress(InetAddress.getLocalHost().getHostAddress(), 9000), 0);
    localNetworkHttpServer.setExecutor(THREAD_POOL_EXECUTOR);
    localNetworkHttpServer.createContext("/applications", new TuningForkHandler());
    localNetworkHttpServer.start();
    localHostHttpServer = HttpServer.create(new InetSocketAddress("localhost", 9000), 0);
    localHostHttpServer.createContext("/applications", new TuningForkHandler());
    localHostHttpServer.setExecutor(THREAD_POOL_EXECUTOR);
    localHostHttpServer.start();
    isBound = true;
  }

  public static RequestServer getInstance() {
    if (!isBound) {
      try {
        requestServer = new RequestServer();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    return requestServer;
  }

  public void setMonitoringAction(Consumer<UploadTelemetryRequest> monitoringAction) {
    this.monitoringAction = Optional.ofNullable(monitoringAction);
  }

  public void setFidelitySupplier(Supplier<ByteString> supplier) {
    this.fidelitySupplier = Optional.ofNullable(supplier);
  }

  public void setDebugInfoAction(Consumer<JsonObject> debugInfoAction) {
    this.debugInfo = Optional.ofNullable(debugInfoAction);
  }

  public void stopListening() {
    if (isBound) {
      localNetworkHttpServer.stop(0);
      localHostHttpServer.stop(0);
      isBound = false;
    } else {
      throw new IllegalStateException("Server is not running!");
    }
  }

  /*
   * Replaces durations that have exponent floating point values, because protocol buffers can't
   * process that.
   */
  private Optional<String> replaceExponentSubstrings(String jsonString) {
    Pattern pattern = Pattern.compile(EXPONENT_PATTERN);
    StringBuffer stringBuffer = new StringBuffer();
    Matcher matcher = pattern.matcher(jsonString);

    boolean found = false;
    while (matcher.find()) {
      found = true;
      String stringToReplace = matcher.group(0);
      stringToReplace = stringToReplace.substring(13, stringToReplace.length() - 2);
      BigDecimal bigDecimal = new BigDecimal(stringToReplace);
      matcher.appendReplacement(stringBuffer, "\"duration\": " + bigDecimal.longValue() + "s");
    }
    matcher.appendTail(stringBuffer);

    if (found) {
      return Optional.of(stringBuffer.toString());
    }
    return Optional.empty();
  }

  private UploadTelemetryRequest parseJsonTelemetry(String jsonString)
      throws InvalidProtocolBufferException {
    UploadTelemetryRequest.Builder telemetryBuilder = UploadTelemetryRequest.newBuilder();
    Optional<String> replacedSubstrings = replaceExponentSubstrings(jsonString);
    jsonString = replacedSubstrings.orElse(jsonString);
    JsonFormat.parser().ignoringUnknownFields().merge(jsonString, telemetryBuilder);
    return telemetryBuilder.build();
  }

  // Respond with code 200 for now
  private void handleDebugInfo(HttpExchange httpExchange) throws IOException {
    InputStream inputStream = httpExchange.getRequestBody();
    JsonObject jsonObject = (JsonObject) new JsonParser().parse(new InputStreamReader(inputStream));
    debugInfo.ifPresent(jsonObjectConsumer -> jsonObjectConsumer.accept(jsonObject));
    httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
    httpExchange.getResponseBody().flush();
    httpExchange.getResponseBody().close();
  }

  private void handleUploadTelemetry(HttpExchange httpExchange) throws IOException {
    String requestBody = new String(ByteStreams.toByteArray(httpExchange.getRequestBody()));
    if (monitoringAction.isPresent()) {
      monitoringAction.get().accept(parseJsonTelemetry(requestBody));
      httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
    } else {
      // Just to keep caching histograms until we need them
      httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_NOT_ACCEPTABLE, 0);
    }
    httpExchange.getResponseBody().close();
  }

  private void handleGenerateTuningParameters(HttpExchange httpExchange) throws IOException {
    ByteString fidelityParam =
        fidelitySupplier.isPresent() ? fidelitySupplier.get().get() : ByteString.EMPTY;
    if (!fidelityParam.equals(ByteString.EMPTY)) {
      String parsedString = Base64.getEncoder()
          .encodeToString(fidelityParam.toByteArray());
      String reply =
          "{\"parameters\": {\"serializedFidelityParameters\": \"" + parsedString + "\"}}";
      httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, reply.length());
      httpExchange.getResponseBody().write(reply.getBytes());
    } else {
      httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_NOT_ACCEPTABLE, 0);
    }
    httpExchange.getResponseBody().flush();
    httpExchange.getResponseBody().close();
  }

  private final class TuningForkHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
      String requestPath = httpExchange.getRequestURI().toASCIIString();
      try {
        if (requestPath.contains(":uploadTelemetry")) {
          handleUploadTelemetry(httpExchange);
        } else if (requestPath.contains(":generateTuningParameters")) {
          handleGenerateTuningParameters(httpExchange);
        } else if (requestPath.contains(":debugInfo")) {
          handleDebugInfo(httpExchange);
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }
}
