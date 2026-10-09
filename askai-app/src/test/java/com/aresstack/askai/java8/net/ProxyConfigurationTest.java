package com.aresstack.askai.java8.net;

import com.aresstack.winproxy.ProxyDefaults;
import com.aresstack.winproxy.ProxyMode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Since win-proxy-java 0.2.0 the WScript PAC discovery is the library's own
 * {@link ProxyMode#PAC_URL_WSCRIPT}; AskAI passes the mode and the script straight through.
 */
public class ProxyConfigurationTest {

    @Test
    public void wscriptModeIsPassedToTheLibraryAsItsOwnMode() {
        ProxyConfiguration cfg = new ProxyConfiguration(ProxyConfiguration.PAC_URL_WSCRIPT, "", "", "", "", 0);

        assertEquals(ProxyMode.PAC_URL_WSCRIPT, cfg.getMode());
        assertEquals(ProxyMode.PAC_URL_WSCRIPT, cfg.toWinProxyConfiguration().getMode());
        assertEquals(ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_WSCRIPT, cfg.getPacUrlDiscoveryScript());
        assertEquals(ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_WSCRIPT, cfg.toWinProxyConfiguration().getPacUrlDiscoveryScript());
    }

    @Test
    public void customWscriptIsPassedThroughUnchanged() {
        String script = "WScript.Echo \"http://proxy.example.com/wpad.dat\"";
        ProxyConfiguration cfg = new ProxyConfiguration(ProxyConfiguration.PAC_URL_WSCRIPT, "", script, "", "", 0);

        assertEquals(script, cfg.toWinProxyConfiguration().getPacUrlDiscoveryScript());
    }

    @Test
    public void powerShellModeKeepsThePowerShellDefaultScript() {
        ProxyConfiguration cfg = new ProxyConfiguration(ProxyConfiguration.PAC_URL_POWERSHELL, "", "", "", "", 0);

        assertEquals(ProxyMode.PAC_URL_POWERSHELL, cfg.getMode());
        assertEquals(ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT, cfg.getPacUrlDiscoveryScript());
    }

    @Test
    public void unknownModeFallsBackToPowerShell() {
        ProxyConfiguration cfg = new ProxyConfiguration("NOT_A_MODE", "", "", "", "", 0);

        assertEquals(ProxyMode.PAC_URL_POWERSHELL, cfg.getMode());
    }
}
