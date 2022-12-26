package info.nightscout.pump.combo.ruffyscripter.commands;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import org.monkey.d.ruffy.ruffy.driver.display.MenuAttribute;
import org.monkey.d.ruffy.ruffy.driver.display.MenuType;
import org.monkey.d.ruffy.ruffy.driver.display.menu.BolusType;
import org.monkey.d.ruffy.ruffy.driver.display.menu.MenuTime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import info.nightscout.pump.combo.ruffyscripter.PumpWarningCodes;
import info.nightscout.pump.combo.ruffyscripter.RuffyScripter;
import info.nightscout.pump.combo.ruffyscripter.WarningOrErrorCode;
import info.nightscout.rx.logging.AAPSLogger;
import info.nightscout.rx.logging.LTag;

public class ExtendedBolusCommand extends BaseCommand {

    private final AAPSLogger aapsLogger;

    protected final double bolus;
    protected final int duration;

    public ExtendedBolusCommand(double bolus, int duration, AAPSLogger aapsLogger) {
        this.bolus = bolus;
        this.duration = duration;
        this.aapsLogger = aapsLogger;
    }

    @Override
    public List<String> validateArguments() {
        List<String> violations = new ArrayList<>();

        if (bolus <= 0) {
            violations.add("Requested bolus non-positive: " + bolus);
        }

        if (duration <= 0) {
            violations.add("Requested duration non-positive: " + duration);
        }

        return violations;
    }

    @Override
    public Integer getReconnectWarningId() {
        return PumpWarningCodes.BOLUS_CANCELLED;
    }

    @Override
    public void execute() {
        enterExtendedBolusMenu();
        inputBolusAmount();
        verifyDisplayedBolusAmount();
        scripter.pressMenuKey();
        inputDuration();
        verifyDisplayedDurationAmount();

        // confirm bolus
        scripter.verifyMenuIsDisplayed(MenuType.BOLUS_DURATION);
        scripter.pressCheckKey();
        aapsLogger.debug("Stage 2: extended bolus confirmed");

        // the extended bolus is displayed on the main menu
        scripter.verifyMenuIsDisplayed(MenuType.MAIN_MENU,
                "Pump did not return to MAIN_MENU from BOLUS_DURATION to deliver extended bolus. "
                        + "Check pump manually, the bolus might not have been delivered.");

        if (scripter.getCurrentMenu().getAttribute(MenuAttribute.BOLUS_TYPE) != BolusType.EXTENDED) {
            throw new CommandException("Could not verify enacted extended bolus on main menu.");
        }
        result.success = true;
    }

    private void enterExtendedBolusMenu() {
        scripter.verifyMenuIsDisplayed(MenuType.MAIN_MENU);
        scripter.navigateToMenu(MenuType.EXTENDED_BOLUS_MENU);
        scripter.verifyMenuIsDisplayed(MenuType.EXTENDED_BOLUS_MENU);
        scripter.pressCheckKey();
        scripter.verifyMenuIsDisplayed(MenuType.BOLUS_ENTER);
    }

    private void inputBolusAmount() {
        scripter.verifyMenuIsDisplayed(MenuType.BOLUS_ENTER);

        long steps = Math.round(bolus * 10);
        for (int i = 0; i < steps; i++) {
            scripter.verifyMenuIsDisplayed(MenuType.BOLUS_ENTER);
            scripter.pressUpKey();
            SystemClock.sleep(50);
        }
    }

    private void verifyDisplayedBolusAmount() {
        scripter.verifyMenuIsDisplayed(MenuType.BOLUS_ENTER);

        // wait up to 10s for any scrolling to finish
        double displayedBolus = scripter.readBlinkingValue(Double.class, MenuAttribute.BOLUS);
        long timeout = System.currentTimeMillis() + 10 * 1000;
        while (timeout > System.currentTimeMillis() && bolus - displayedBolus > 0.05) {
            aapsLogger.debug("Waiting for pump to process scrolling input for amount, current: " + displayedBolus + ", desired: " + bolus);
            SystemClock.sleep(50);
            displayedBolus = scripter.readBlinkingValue(Double.class, MenuAttribute.BOLUS);
        }

        aapsLogger.debug("Final bolus: " + displayedBolus);
        if (Math.abs(displayedBolus - bolus) > 0.01) {
            throw new CommandException("Failed to set correct bolus. Expected: " + bolus + ", actual: " + displayedBolus);
        }

        // check again to ensure the displayed value hasn't change due to due scrolling taking extremely long
        SystemClock.sleep(1000);
        scripter.verifyMenuIsDisplayed(MenuType.BOLUS_ENTER);
        double refreshedDisplayedBolus = scripter.readBlinkingValue(Double.class, MenuAttribute.BOLUS);
        if (Math.abs(displayedBolus - refreshedDisplayedBolus) > 0.01) {
            throw new CommandException("Failed to set bolus: bolus changed after input stopped from "
                    + displayedBolus + " -> " + refreshedDisplayedBolus);
        }
    }

    private void inputDuration() {
        scripter.verifyMenuIsDisplayed(MenuType.BOLUS_DURATION);

        long steps = Math.round(duration / 15) - 1;
        for (int i = 0; i < steps; i++) {
            scripter.verifyMenuIsDisplayed(MenuType.BOLUS_DURATION);
            scripter.pressUpKey();
            SystemClock.sleep(50);
        }
    }

    private void verifyDisplayedDurationAmount() {
        scripter.verifyMenuIsDisplayed(MenuType.BOLUS_DURATION);

        // wait up to 10s for any scrolling to finish
        double displayedDuration = readDisplayedDuration();
        long timeout = System.currentTimeMillis() + 10 * 1000;
        while (timeout > System.currentTimeMillis() && duration - displayedDuration > 1) {
            aapsLogger.debug("Waiting for pump to process scrolling input for duration, current: " + displayedDuration + ", desired: " + duration);
            SystemClock.sleep(50);
            displayedDuration = readDisplayedDuration();
        }

        aapsLogger.debug("Final duration: " + displayedDuration);
        if (Math.abs(displayedDuration - duration) > 1) {
            throw new CommandException("Failed to set correct duration. Expected: " + duration + ", actual: " + displayedDuration);
        }

        // check again to ensure the displayed value hasn't change due to due scrolling taking extremely long
        SystemClock.sleep(1000);
        scripter.verifyMenuIsDisplayed(MenuType.BOLUS_DURATION);
        double refreshedDisplayedDuration = readDisplayedDuration();
        if (Math.abs(displayedDuration - refreshedDisplayedDuration) > 1) {
            throw new CommandException("Failed to set bolus: bolus changed after input stopped from "
                    + displayedDuration + " -> " + refreshedDisplayedDuration);
        }
    }

    private long readDisplayedDuration() {
        MenuTime duration = scripter.readBlinkingValue(MenuTime.class, MenuAttribute.RUNTIME);
        return duration.getHour() * 60 + duration.getMinute();
    }

    @Override
    public String toString() {
        return "ExtendedBolusCommand{" +
                "bolus=" + bolus + " " +
                "duration" + duration +
                '}';
    }
}
