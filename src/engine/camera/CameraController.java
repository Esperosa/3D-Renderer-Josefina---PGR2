package engine.camera;

import engine.core.Input;
import engine.math.MathUtil;
import engine.math.Vec3;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

/**
 * Tady zpracovávám pohyb a rotaci kamery z uživatelského vstupu.
 */
public class CameraController {

    /**
     * Tady rozliším režim ovládání kamery.
     */
    public enum Mode {
        /** Tady používám volný pohled přes WASD a myš. */
        FREE_LOOK,
        /** Tady držím kameru na objektu a myší otáčím pohled. */
        FIRST_PERSON,
        /** Tady obíhám kolem cílového bodu. */
        ORBIT
    }

    private final Camera camera;
    private Mode mode;
    private double moveSpeed;
    private double rotateSpeed;
    private double orbitDistance;
    private Vec3 orbitTarget;
    private double yaw;
    private double pitch;
    private boolean mouseLookAlways;

    public CameraController(Camera camera, Mode mode) {
        this.camera = camera;
        this.mode = mode;
        this.moveSpeed = 2.2;
        this.rotateSpeed = 0.0012;
        this.orbitTarget = new Vec3(0.0, 0.8, 0.0);
        this.yaw = 0.0;
        this.pitch = 0.0;
        this.orbitDistance = 1.0;
        syncStateFromCamera();
        this.mouseLookAlways = false;
    }

    /**
     * Tady zpracovávám vstup a aktualizuju pozici i orientaci kamery.
     *
     * @param input sem předám stav vstupu pro aktuální snímek
     * @param dt sem předám delta čas v sekundách
     */
    public void update(Input input, double dt) {
        if (mode == Mode.ORBIT) {
            updateOrbit(input, dt);
            return;
        }
        updateFreeLike(input, dt, mode == Mode.FIRST_PERSON);
    }

    private void updateFreeLike(Input input, double dt, boolean lockY) {
        boolean rotateCamera = mouseLookAlways;
        if (rotateCamera) {
            double dx = MathUtil.clamp(input.getMouseDX(), -60.0, 60.0);
            double dy = MathUtil.clamp(input.getMouseDY(), -60.0, 60.0);
            yaw += dx * rotateSpeed;
            pitch = MathUtil.clamp(pitch - dy * rotateSpeed, -1.54, 1.54);
        } else {
            // Tady používám náhradní rozhlížení z klávesnice, když myší zrovna neotáčím.
            double keyYaw = 0.0;
            double keyPitch = 0.0;
            if (input.isKeyDown(KeyEvent.VK_J)) {
                keyYaw -= 1.0;
            }
            if (input.isKeyDown(KeyEvent.VK_L)) {
                keyYaw += 1.0;
            }
            if (input.isKeyDown(KeyEvent.VK_I)) {
                keyPitch += 1.0;
            }
            if (input.isKeyDown(KeyEvent.VK_K) && !input.isKeyPressed(KeyEvent.VK_K)) {
                keyPitch -= 1.0;
            }
            double keyLookSpeed = 1.8;
            yaw += keyYaw * keyLookSpeed * dt;
            pitch = MathUtil.clamp(pitch + keyPitch * keyLookSpeed * dt, -1.54, 1.54);
        }

        Vec3 forward = fromYawPitch(yaw, pitch).normalize();
        Vec3 right = forward.cross(Vec3.UP).normalize();
        if (right.lengthSquared() < 1e-8) {
            right = new Vec3(1.0, 0.0, 0.0);
        }

        double speed = input.isShiftDown() ? moveSpeed * 1.7 : moveSpeed;
        Vec3 delta = Vec3.ZERO;
        if (input.isKeyDown(KeyEvent.VK_W) || input.isKeyDown(KeyEvent.VK_UP)) {
            delta = delta.add(lockY ? flatten(forward) : forward);
        }
        if (input.isKeyDown(KeyEvent.VK_S) || input.isKeyDown(KeyEvent.VK_DOWN)) {
            delta = delta.sub(lockY ? flatten(forward) : forward);
        }
        if (input.isKeyDown(KeyEvent.VK_A) || input.isKeyDown(KeyEvent.VK_LEFT)) {
            delta = delta.sub(right);
        }
        if (input.isKeyDown(KeyEvent.VK_D) || input.isKeyDown(KeyEvent.VK_RIGHT)) {
            delta = delta.add(right);
        }
        if (!lockY && input.isKeyDown(KeyEvent.VK_SPACE)) {
            delta = delta.add(Vec3.UP);
        }
        if (!lockY && input.isKeyDown(KeyEvent.VK_CONTROL)) {
            delta = delta.sub(Vec3.UP);
        }
        if (delta.lengthSquared() > 0.0) {
            camera.setPosition(camera.getPosition().add(delta.normalize().mul(speed * dt)));
        }
        camera.lookAt(camera.getPosition().add(forward));
    }

    private void updateOrbit(Input input, double dt) {
        boolean orbitDrag = input.isMouseButtonDown(MouseEvent.BUTTON2) && !input.isShiftDown();
        boolean panDrag = input.isMouseButtonDown(MouseEvent.BUTTON2) && input.isShiftDown();

        if (orbitDrag) {
            double dx = MathUtil.clamp(input.getMouseDX(), -60.0, 60.0);
            double dy = MathUtil.clamp(input.getMouseDY(), -60.0, 60.0);
            yaw += dx * rotateSpeed;
            pitch = MathUtil.clamp(pitch - dy * rotateSpeed, -1.54, 1.54);
        } else if (panDrag) {
            Vec3 forward = fromYawPitch(yaw, pitch).normalize();
            Vec3 right = forward.cross(Vec3.UP).normalize();
            Vec3 up = right.cross(forward).normalize();
            double panScale = orbitDistance * 0.0025;
            orbitTarget = orbitTarget
                    .sub(right.mul(input.getMouseDX() * panScale))
                    .add(up.mul(input.getMouseDY() * panScale));
        }

        int scroll = input.getScrollDelta();
        if (scroll != 0) {
            double zoomScale = Math.exp(scroll * 0.11);
            orbitDistance = MathUtil.clamp(orbitDistance * zoomScale, 0.25, 1000.0);
        }

        if (input.isKeyDown(KeyEvent.VK_W)) {
            orbitDistance = Math.max(0.25, orbitDistance - moveSpeed * dt * 2.0);
        }
        if (input.isKeyDown(KeyEvent.VK_S)) {
            orbitDistance = Math.min(1000.0, orbitDistance + moveSpeed * dt * 2.0);
        }

        Vec3 forward = fromYawPitch(yaw, pitch).normalize();
        Vec3 camPos = orbitTarget.sub(forward.mul(orbitDistance));
        camera.setPosition(camPos);
        camera.lookAt(orbitTarget);
    }

    private Vec3 fromYawPitch(double yaw, double pitch) {
        double cp = Math.cos(pitch);
        return new Vec3(
                Math.sin(yaw) * cp,
                Math.sin(pitch),
                -Math.cos(yaw) * cp
        );
    }

    private Vec3 flatten(Vec3 v) {
        Vec3 out = new Vec3(v.x, 0.0, v.z);
        if (out.lengthSquared() < 1e-8) {
            return new Vec3(0.0, 0.0, -1.0);
        }
        return out.normalize();
    }

    public void snapView(Vec3 direction, boolean keepDistance) {
        Vec3 dir = direction.normalize();
        yaw = Math.atan2(dir.x, -dir.z);
        pitch = Math.asin(MathUtil.clamp(dir.y, -1.0, 1.0));
        if (mode == Mode.ORBIT || keepDistance) {
            Vec3 pos = orbitTarget.sub(dir.mul(orbitDistance));
            camera.setPosition(pos);
            camera.lookAt(orbitTarget);
        } else {
            camera.lookAt(camera.getPosition().add(dir));
        }
    }

    public void frameTarget(Vec3 target) {
        orbitTarget = target;
        if (mode == Mode.ORBIT) {
            Vec3 dir = fromYawPitch(yaw, pitch).normalize();
            camera.setPosition(orbitTarget.sub(dir.mul(orbitDistance)));
            camera.lookAt(orbitTarget);
        }
    }

    // Tady držím základní přístupové metody.
    public void setMode(Mode mode) {
        this.mode = mode;
        if (mode == Mode.ORBIT) {
            orbitDistance = Math.max(0.2, camera.getPosition().sub(orbitTarget).length());
        }
        syncStateFromCamera();
    }

    public Mode getMode() {
        return mode;
    }

    public void setMoveSpeed(double s) {
        this.moveSpeed = Math.max(0.1, s);
    }

    public double getMoveSpeed() {
        return moveSpeed;
    }

    public void setRotateSpeed(double s) {
        this.rotateSpeed = Math.max(1e-5, s);
    }

    public double getRotateSpeed() {
        return rotateSpeed;
    }

    public void setOrbitTarget(Vec3 target) {
        this.orbitTarget = target;
    }

    public Vec3 getOrbitTarget() {
        return orbitTarget;
    }

    public boolean isMouseLookAlways() {
        return mouseLookAlways;
    }

    public void setMouseLookAlways(boolean mouseLookAlways) {
        this.mouseLookAlways = mouseLookAlways;
    }

    public final void syncStateFromCamera() {
        Vec3 dir = camera.getForward().normalize();
        if (dir.lengthSquared() < 1e-10) {
            dir = new Vec3(0.0, 0.0, -1.0);
        }
        this.yaw = Math.atan2(dir.x, -dir.z);
        this.pitch = Math.asin(MathUtil.clamp(dir.y, -1.0, 1.0));
        this.orbitDistance = Math.max(0.2, camera.getPosition().sub(orbitTarget).length());
    }
}
