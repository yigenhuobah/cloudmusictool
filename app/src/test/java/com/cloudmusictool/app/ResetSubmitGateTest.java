package com.cloudmusictool.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** “恢复默认”提交门：同一时间只允许一个 reset 入队。 */
public class ResetSubmitGateTest {

    @Test
    public void begin_rejectsSecondSubmitUntilFinished() {
        ResetSubmitGate gate = new ResetSubmitGate();

        assertFalse(gate.inFlight());
        assertTrue("首次提交必须被接受", gate.begin());
        assertTrue(gate.inFlight());
        assertFalse("在途时第二次提交必须被拒绝", gate.begin());

        gate.finish();
        assertFalse(gate.inFlight());
        assertTrue("上一次结束后允许重试", gate.begin());
    }
}
