// Compile-only AOSP API declarations. Never package these classes in an APK.
// Source ABI: packages/modules/Virtualization @ 175a51b30123fa6b02b541f1969665708f7ec2c3.
package android.system.virtualmachine;

public final class VirtualMachineConfig {
    public static final int CPU_TOPOLOGY_ONE_CPU = 0;
    public static final int CPU_TOPOLOGY_MATCH_HOST = 1;
    public static final int DEBUG_LEVEL_NONE = 0;
    public static final class Builder {
        public Builder(android.content.Context context) { throw new UnsupportedOperationException("Compile-only API"); }
        public Builder setProtectedVm(boolean enabled) { throw new UnsupportedOperationException("Compile-only API"); }
        public Builder setMemoryBytes(long bytes) { throw new UnsupportedOperationException("Compile-only API"); }
        public Builder setCpuTopology(int topology) { throw new UnsupportedOperationException("Compile-only API"); }
        public Builder setCustomImageConfig(VirtualMachineCustomImageConfig config) { throw new UnsupportedOperationException("Compile-only API"); }
        public Builder setDebugLevel(int level) { throw new UnsupportedOperationException("Compile-only API"); }
        public Builder setVmOutputCaptured(boolean enabled) { throw new UnsupportedOperationException("Compile-only API"); }
        public Builder setConnectVmConsole(boolean enabled) { throw new UnsupportedOperationException("Compile-only API"); }
        public VirtualMachineConfig build() { throw new UnsupportedOperationException("Compile-only API"); }
    }
}
