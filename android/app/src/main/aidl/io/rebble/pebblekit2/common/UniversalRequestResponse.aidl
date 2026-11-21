// From PebbleKit Android 2 (https://github.com/pebble-dev/PebbleKitAndroid2),
// Apache License 2.0. The package and names must stay as they are: they are the
// wire contract with the Pebble phone app.
package io.rebble.pebblekit2.common;

import io.rebble.pebblekit2.common.SendDataCallback;

interface UniversalRequestResponse {
    void request(in Bundle data, in SendDataCallback callback);
}
