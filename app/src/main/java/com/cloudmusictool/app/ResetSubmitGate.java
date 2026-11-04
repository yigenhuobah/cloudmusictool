package com.cloudmusictool.app;

/**
 * “恢复默认”提交门（主线程使用）：in-flight 时拒绝再次提交，
 * 防止多个确认框把同一个 reset 任务重复入队。
 */
final class ResetSubmitGate {

    private boolean mInFlight;

    /** @return true 表示本次提交被接受；false 表示已有提交在途。 */
    boolean begin() {
        if (mInFlight) {
            return false;
        }
        mInFlight = true;
        return true;
    }

    void finish() {
        mInFlight = false;
    }

    boolean inFlight() {
        return mInFlight;
    }
}
