package dev.moduforge.sandbox.ipc;

import android.os.ParcelFileDescriptor;
import dev.moduforge.sandbox.ipc.IHostBridge;
import dev.moduforge.sandbox.ipc.IResultCallback;

/** Control surface of one sandbox process, called by the host. */
interface ISandbox {
    /** Loads the module code from modulePackage (read-only descriptor of the module package). */
    oneway void load(in ParcelFileDescriptor modulePackage, String manifestJson, IHostBridge bridge, IResultCallback callback);

    /** phase: name of a LifecyclePhase. Completes when the module callback returns. */
    oneway void invoke(String phase, IResultCallback callback);

    /** eventJson: serialized UiEvent. */
    oneway void uiEvent(String eventJson);
}
