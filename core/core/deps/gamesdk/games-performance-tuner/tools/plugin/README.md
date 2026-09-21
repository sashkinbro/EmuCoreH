# Android Performance Tuner Plugin For Android Studio

This plugin helps game developers integrate APT in their Android Studio projects.
 The plugin also provide debugging features for TuningFork by allowing the user to
 intercept all the outgoing HTTP requests and monitor them on the plugin such as.

 * Monitoring Renderer Time Histograms
 * Debug Information sent from APK
 * Downloading Fidelity From Server.

## Setup
Setting up the project requires changing the plugin local android studio build.
* Clone the repo.
* Open up the project using intelliJ(Community or Ultimate).
* Open up local.properties (If it doesn't exist create one).
* Add android.studio.dir.
* Set android.studio.dir to local Android Studio Path.

This is needed because the plugin needs C++ Idea Api which only exists in Android Studio
/ CLion platform

**Example**

android.studio.dir=/Applications/Android Studio.app/Contents

##### DON'T add local.properties to version control

## Launching In Development Mode
* Complete all the steps in the 'Setup' section.
* Open the list of gradle tasks.
* Execute 'runIde' task which is responsible for running the plugin in testing environment.
* The plugin will be run using the local android studio version specified in the 'Setup' procedure.

## Supported Features

* Allows adding/removing/editing Enums and their options.
* Allows adding/removing/editing Annotation fields.
* Allows adding/removing/editing Fidelity fields(Int/Float/Enum).
* Allows adding/removing/editing Quality levels with different parameters.
* Contains local debugging tool and monitoring tab to analyse render time histograms
* Shows the debug information sent from TuningFork as human-readable text.
* Enables the user to change 'Fidelity' settings in APK mid-game from the plugin directly.
* Has warnings for any unrecommended settings. Such as:
    * Too small upload interval which might lead to bandwidth abuse.
    * Histograms not covering the [30,60] FPS range.

## Monitoring Tab

The monitoring tab heavily depends on the Histogram tree. Once a request has been received.
It's converted to a tree of nodes. For example, Let's say we got a request with the following
features.

Phone Model: PM

Telemetries:
* Telemetry 1
    * Annotation 1
    * Fidelity 1
    * ID1 -> Values
    * ID2 -> Values
* Telemetry 2
    * Annotation 1
    * Fidelity 2
    * ID1 -> Values

This is converted into nodes with the following hierarchy.

__Only leaf node has values__
 ```
  Phone Node(Name: PM, Tooltip: Device information)
  |- Annotation 1(Tooltip: annotation as text)
  |  |- Fidelity 1(Tooltip: fidelity as text)
  |  |  |- ID1(Has no tooltip, but has value which is the list of integers)
  |  |  |- ID2(Has no tooltip, but has value which is the list of integers)
  |- Annotation 1(Tooltip: annotation as text)
  |  |- Fidelity 2(Tooltip: fidelity as text)
  |     |- ID1(has no tooltip, but has value which is the list of integers)
  ```
After the nodes have been constructed in this shape. It's added to the histogram tree.

__Histogram Tree Doesn't Allow Duplicate Nodes__.

So If there's duplicate nodes, They'll be merged together. So the final result in the Histogram tree
would be like this.
```
  Invisible Root
  |- Phone Node(Same)
  |  |- Annotation 1(Same tooltip)
  |  |  |- Fidelity 1(Same tooltip)
  |  |  |  |- ID1
  |  |  |  |- ID2
  |  |  |- Fidelity 2(Same tooltip)
  |  |  |  |- ID1
```
If the plugin received __Phone Node->Annotation1->Fidelity1->ID1__, Then It'd be merged with the
already existing node in the histogram by adding both values together.

Once the histogram tree has been constructed. It's used to render "Data Collected" Tree.
Then all filters will be rechecked and updated with the new chart panels according to the new
tree values.

Monitoring tab also supports loading/saving reports. __Loaded Reports are static__, They
can't be refreshed, You can only add/remove filters to them.

### How To Start Monitoring

* Set the endpoint uri to "http://10.0.2.2:9000", Which means the endpoint address is
10.0.2.2, And the listening port is 9000
* Make sure to set low upload interval. Somewhere around 10-30 seconds.
* Click 'OK' to save the settings and start the apk.
* Start the plugin
* Go to the monitoring tab
* Hit "Start Monitoring"
* Wait until it receives any data.

Monitoring is basically a server bound to the localhost(And local network) listening to all incoming HTTP requests
from TuningFork. So If you have more than one device sending requests, then the plugin will view them
in the 'Collected Data' List, Plugin must be running while data is being sent and all data is lost
once plugin is closed or stop monitoring has been hit. Data can be saved for later review but no
new data can be added to a loaded report.
##### Troubleshooting Monitoring

If no data was received in a minute interval then.

* Check the endpoint uri again and make sure it's "http://10.0.2.2:9000"
* Check the upload interval.
* If android device is used instead of emulator, Try settings the endpoint uri to
"http://localNetworkIpAddress:9000", Replace the "localNetworkIpAddress" with your local ip address.

For example: "http://192.168.0.113:9000"

### Debug Info

Tuningfork by default sends DebugInfo along with the "generateTuningParameter" Request.
So It's only sent once at the start of the apk, So In order to see the debugInfo,
User must call "TuningFork_getFidelityParameters" method from the code __WHILE THE PLUGIN
IS UP AND RUNNING__

Sample JNI Code for calling Debug Info & Changing Fidelity
```
    // A blocking method that must be called on separate thread.
    JNIEXPORT void JNICALL
    Java_com_tuningfork_experimentsdemo_TFTestActivity_getFidelityParameters(JNIEnv * env, jclass clz) {
        auto* defaults = new TuningFork_CProtobufSerialization();
        auto* s = new TuningFork_CProtobufSerialization();
        TuningFork_ErrorCode result = TuningFork_getFidelityParameters(defaults, s, 15000);
        FidelityParams fidelity;
        fidelity.ParseFromArray(s->bytes, s->size);
        FidelityParamsCallback(s);
    }
```

### Fidelity Changer

In order to change the fidelity params, You have to follow those steps

* Open the plugin.
* Open the "Fidelity Changer" Tab.
* Choose one of the quality levels then hit "Checkmark".
* Call getFidelityParameters method from the APK __While the plugin is running__

You can find a sample code for getFidelityParameters in the above section.