// Compile-only AOSP API declarations. Never package these classes in an APK.
// Source ABI: packages/modules/Virtualization @ 175a51b30123fa6b02b541f1969665708f7ec2c3.
package android.system.virtualmachine;

public class VirtualMachineCustomImageConfig {
    public static final class SharedPath {
        public SharedPath(String path, int hostUid, int hostGid, int guestUid, int guestGid,
                int mask, String tag, String socket, boolean appDomain, String socketPath) {
            throw new UnsupportedOperationException("Compile-only API");
        }
    }
    public static final class Disk {
        public static Disk RODisk(String path) { throw new UnsupportedOperationException("Compile-only API"); }
        public static Disk RWDisk(String path) { throw new UnsupportedOperationException("Compile-only API"); }
    }
    public static final class Builder {
        public Builder() { throw new UnsupportedOperationException("Compile-only API"); }
        public Builder setName(String name) { throw new UnsupportedOperationException("Compile-only API"); }
        public Builder setKernelPath(String path) { throw new UnsupportedOperationException("Compile-only API"); }
        public Builder setInitrdPath(String path) { throw new UnsupportedOperationException("Compile-only API"); }
        public Builder addParam(String param) { throw new UnsupportedOperationException("Compile-only API"); }
        public Builder useNetwork(boolean enabled) { throw new UnsupportedOperationException("Compile-only API"); }
        public Builder useAutoMemoryBalloon(boolean enabled) { throw new UnsupportedOperationException("Compile-only API"); }
        public Builder addDisk(Disk disk) { throw new UnsupportedOperationException("Compile-only API"); }
        public Builder addSharedPath(SharedPath path) { throw new UnsupportedOperationException("Compile-only API"); }
        public VirtualMachineCustomImageConfig build() { throw new UnsupportedOperationException("Compile-only API"); }
    }
}
