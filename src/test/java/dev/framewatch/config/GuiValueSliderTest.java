package dev.framewatch.config;

import dev.framewatch.client.GuiValueSlider;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuiValueSliderTest {
    @Test public void clickingTrackSnapsToStepAndOnlyReportsActualChanges() {
        AtomicReference<Double> value = new AtomicReference<>(0.8);
        AtomicInteger changes = new AtomicInteger();
        GuiValueSlider slider = new GuiValueSlider(0, 10, 20, 206, 0.5, 2.5, 0.05,
                value::get, v -> { value.set(v); changes.incrementAndGet(); }, () -> "scale");
        // With a 200px track, each 5px advances one 0.05 detent.
        assertTrue(slider.mousePressed(null, 43, 25));
        assertEquals(0.8, value.get(), 0.000001);
        assertEquals(0, changes.get());
        slider.mouseReleased(43, 25);
        assertTrue(slider.mousePressed(null, 98, 25));
        assertEquals(1.35, value.get(), 0.000001);
        assertEquals(1, changes.get());
        slider.mouseReleased(98, 25);
        assertTrue(slider.mousePressed(null, 98, 25));
        assertEquals(1, changes.get());
    }

    @Test public void endpointsClampAndDisabledOrOutsideClicksDoNotEdit() {
        AtomicReference<Double> value = new AtomicReference<>(125.0);
        GuiValueSlider slider = new GuiValueSlider(0, 10, 20, 206, 125, 1000, 25,
                value::get, value::set, () -> "text refresh");
        assertFalse(slider.mousePressed(null, 9, 25));
        assertFalse(slider.mousePressed(null, 20, 40));
        assertEquals(125, value.get(), 0);
        assertTrue(slider.mousePressed(null, 215, 25));
        assertEquals(1000, value.get(), 0);
        slider.mouseReleased(215, 25);
        assertTrue(slider.mousePressed(null, 10, 25));
        assertEquals(125, value.get(), 0);
        slider.mouseReleased(10, 25);
        slider.enabled = false;
        assertFalse(slider.mousePressed(null, 215, 25));
        assertEquals(125, value.get(), 0);
    }

    @Test public void graphAxisAutoDetentMapsToZeroAndManualStartsAtThirty() {
        AtomicReference<Double> axis = new AtomicReference<>(0.0);
        GuiValueSlider slider = new GuiValueSlider(0, 10, 20, 227, 29, 250, 1,
                () -> axis.get() == 0 ? 29 : axis.get(), v -> axis.set(v < 30 ? 0.0 : v), () -> "axis");
        assertTrue(slider.mousePressed(null, 14, 25));
        assertEquals(30, axis.get(), 0);
        slider.mouseReleased(14, 25);
        assertTrue(slider.mousePressed(null, 13, 25));
        assertEquals(0, axis.get(), 0);
    }
}
