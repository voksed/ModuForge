package dev.moduforge.sandbox.ipc;

import android.os.ParcelFileDescriptor;

/** Result of IHostBridge.connect. Exactly one method is invoked, once. */
oneway interface IConnectCallback {
    /** stream: local socket relayed by the host to the remote peer. */
    void onConnected(in ParcelFileDescriptor stream);

    /** notGranted: the failure is a missing NETWORK_OUTBOUND grant. */
    void onFailure(boolean notGranted, String message);
}
