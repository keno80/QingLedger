# Shizuku instantiates this Binder implementation reflectively in a separate process.
-keep class io.qingledger.automation.shizuku.ReadOnlyCaptureUserService {
    public <init>();
    public *;
}
