package dev.terminal.runtime.ipc;

/** Invalidation only; reconnecting clients read the authoritative snapshot. */
oneway interface ILocalServicesListener {
    void onServicesChanged();
}
