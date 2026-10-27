package io.qingledger.automation.shizuku;

import android.os.Bundle;
import io.qingledger.automation.shizuku.ReadOnlyCaptureResult;

/** Deliberately exposes no shell command, input injection, or filesystem API. */
interface IReadOnlyCaptureService {
    Bundle probe() = 0;
    ReadOnlyCaptureResult capture(String expectedWindowToken, int expectedDisplayId) = 1;
    void destroy() = 16777114;
}
