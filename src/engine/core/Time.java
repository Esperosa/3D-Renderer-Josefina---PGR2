package engine.core;

/**
 * sleduju čas s vysokým rozlišením pro snímky i logiku pevných kroků.
 */
public class Time {

    private static final double MAX_DELTA = 0.25;

    private long lastFrameNanos;
    private double deltaTime;
    private double elapsedTime;
    private long frameCount;
    private double fixedTimeStep;
    private double accumulator;

    public Time() {
        this(1.0 / 60.0);
    }

    public Time(double fixedTimeStep) {
        this.lastFrameNanos = -1L;
        this.deltaTime = 0.0;
        this.elapsedTime = 0.0;
        this.frameCount = 0L;
        this.fixedTimeStep = Math.max(1e-4, fixedTimeStep);
        this.accumulator = 0.0;
    }

 /**
 * jednou za snímek aktualizuje delta čas a doplním akumulátor pevných kroků.
 */
    public void tick() {
        long now = System.nanoTime();
        if (lastFrameNanos < 0L) {
            lastFrameNanos = now;
            deltaTime = 0.0;
            return;
        }

        double dt = (now - lastFrameNanos) * 1e-9;
        lastFrameNanos = now;
        if (dt < 0.0) {
            dt = 0.0;
        } else if (dt > MAX_DELTA) {
            dt = MAX_DELTA;
        }

        deltaTime = dt;
        elapsedTime += dt;
        accumulator = Math.min(accumulator + dt, 0.5);
        frameCount++;
    }

 /** @return vrátí čas od minulého snímku v sekundách */
    public double getDeltaTime() {
        return deltaTime;
    }

 /** @return vrátí celkový uplynulý čas od startu enginu v sekundách */
    public double getElapsedTime() {
        return elapsedTime;
    }

 /** @return vrátí pevný fyzikální krok v sekundách */
    public double getFixedTimeStep() {
        return fixedTimeStep;
    }

    public void setFixedTimeStep(double fixedTimeStep) {
        this.fixedTimeStep = Math.max(1e-4, fixedTimeStep);
    }

 /**
 * spotřebuju jeden fixed timestep z akumulátoru.
 *
 * @return vrátí true, když mám k dispozici další pevný krok
 */
    public boolean consumeFixedStep() {
        if (accumulator + 1e-12 >= fixedTimeStep) {
            accumulator -= fixedTimeStep;
            return true;
        }
        return false;
    }

 /** @return vrátí celkový počet vykreslených snímků */
    public long getFrameCount() {
        return frameCount;
    }
}