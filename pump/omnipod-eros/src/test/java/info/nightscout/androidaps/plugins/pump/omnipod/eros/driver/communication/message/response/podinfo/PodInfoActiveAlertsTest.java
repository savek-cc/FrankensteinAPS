package info.nightscout.androidaps.plugins.pump.omnipod.eros.driver.communication.message.response.podinfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.joda.time.Duration;
import org.junit.jupiter.api.Test;

import java.util.List;

import info.nightscout.pump.core.utils.ByteUtil;

public class PodInfoActiveAlertsTest {
    @Test
    public void testNoActiveAlerts() {
        byte[] encodedMessage = ByteUtil.fromHexString("01000000000000000000000000000000000000"); // from https://github.com/ps2/rileylink_ios/blob/omnipod-testing/OmniKitTests/PodInfoTests.swift
        PodInfoActiveAlerts podInfoActiveAlerts = new PodInfoActiveAlerts(encodedMessage);

        List<PodInfoActiveAlerts.AlertActivation> alertActivations = podInfoActiveAlerts.getAlertActivations();
        assertEquals(0, alertActivations.size());
    }

    @Test
    public void testChangePodAfter3Days() {
        byte[] encodedMessage = ByteUtil.fromHexString("010000000000000000000000000000000010e1"); // from https://github.com/ps2/rileylink_ios/blob/omnipod-testing/OmniKitTests/PodInfoTests.swift
        PodInfoActiveAlerts podInfoActiveAlerts = new PodInfoActiveAlerts(encodedMessage);

        List<PodInfoActiveAlerts.AlertActivation> alertActivations = podInfoActiveAlerts.getAlertActivations();
        assertEquals(1, alertActivations.size());

        PodInfoActiveAlerts.AlertActivation alertActivation = alertActivations.get(0);
        Duration expectedDuration = Duration.standardHours(72).plus(Duration.standardMinutes(1));
        assertTrue(expectedDuration.isEqual(alertActivation.getValueAsDuration()));
    }
}
