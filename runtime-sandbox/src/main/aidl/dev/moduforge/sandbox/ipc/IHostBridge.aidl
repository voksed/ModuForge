package dev.moduforge.sandbox.ipc;

import dev.moduforge.sandbox.ipc.IConnectCallback;
import dev.moduforge.sandbox.ipc.IResultCallback;

/**
 * Host services reachable from a sandbox. One instance per sandbox; the host binds it
 * to the module identity, so a module cannot act on behalf of another one.
 */
interface IHostBridge {
    /** requestJson: serialized CapabilityRequest. Success payload: serialized CapabilityResult. */
    oneway void requestCapability(String requestJson, IResultCallback callback);

    boolean isGranted(String capability, String target);

    /** level: ordinal of ModuleLogSink.Level. */
    oneway void log(int level, String message);

    /** treeJson: serialized UiNode, or null to remove the module's UI. */
    oneway void showUi(String treeJson);

    /** Opens a connection through the host. Needs NETWORK_OUTBOUND. */
    oneway void connect(String host, int port, boolean tls, IConnectCallback callback);

    // Module storage. Needs FILE_SANDBOXED; a missing grant is reported as SecurityException,
    // an invalid path or an exceeded limit as IllegalArgumentException.

    /** Returns null when the file does not exist. */
    byte[] storageRead(String path);

    void storageWrite(String path, in byte[] data);

    boolean storageDelete(String path);

    String[] storageList();

    /**
     * Shows a notification. Needs NOTIFICATIONS; a missing grant is reported as SecurityException,
     * notifications being turned off for the host as IllegalStateException.
     */
    void notifyUser(String title, String text);

    /** Asks the user a question. Success payload: the answer. Failure: the question was dismissed. */
    oneway void askUser(String question, boolean secret, IResultCallback callback);

    /**
     * A call to a device service (`apps`, `screen`, `camera`). Returns the JSON result. A missing grant is
     * reported as SecurityException, any other failure as IllegalStateException with the reason.
     */
    String deviceCall(String service, String method, String argsJson);

    /** The module has nothing left to do and asks to be stopped. */
    oneway void stopSelf(String reason);
}
