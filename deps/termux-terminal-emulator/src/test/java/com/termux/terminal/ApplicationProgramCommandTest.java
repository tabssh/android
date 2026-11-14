package com.termux.terminal;

/**
 * "\033_", "\033^" and "\033X" start strings (APC, PM, SOS) that a terminal without the matching
 * feature must swallow up to the string terminator instead of printing them.
 */
public class ApplicationProgramCommandTest extends TerminalTestCase {

	public void testKittyGraphicsProbeIsNotPrinted() {
		withTerminalSized(4, 2).enterString("A\033_Gi=31,s=1,v=1,a=q,t=d,f=24;AAAA\033\\B");
		assertLinesAre("AB  ", "    ");
	}

	public void testPrivacyMessageAndStartOfStringAreSwallowed() {
		withTerminalSized(10, 2).enterString("1\033^secret\033\\2\033Xdata\033\\3");
		assertLinesAre("123       ", "          ");
	}

	public void testBellInsideStringDoesNotRing() {
		withTerminalSized(10, 2).enterString("\033_a\007b\033\\C");
		assertLinesAre("C         ", "          ");
	}
}
