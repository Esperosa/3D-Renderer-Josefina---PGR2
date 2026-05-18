import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;

enum TransformMode {
    TRANSLATE("posun"),
    ROTATE("rotace"),
    SCALE("scale");

    final String label;

    TransformMode(String label) {
        this.label = label;
    }
}

final class ViewportPanel extends JPanel {
    private final SceneModel scene;
    private final Camera camera = new Camera();
    private final SoftwareRasterizer rasterizer = new SoftwareRasterizer();
    private final Set<Integer> pressed = new HashSet<>();
    private TransformMode transformMode = TransformMode.TRANSLATE;
    private Runnable selectionListener = () -> {
    };
    private long lastNanos = System.nanoTime();
    private int lastMouseX;
    private int lastMouseY;

    ViewportPanel(SceneModel scene) {
        this.scene = scene;
        setFocusable(true);
        setDoubleBuffered(true);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                boolean first = pressed.add(e.getKeyCode());
                if (first) {
                    handleToggleKey(e.getKeyCode());
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                pressed.remove(e.getKeyCode());
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                lastMouseX = e.getX();
                lastMouseY = e.getY();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                int owner = rasterizer.pickOwner(e.getX(), e.getY());
                if (owner > 0 && owner <= scene.entities.size()) {
                    scene.selectedIndex = owner - 1;
                    selectionListener.run();
                }
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                int dx = e.getX() - lastMouseX;
                int dy = e.getY() - lastMouseY;
                lastMouseX = e.getX();
                lastMouseY = e.getY();
                camera.yaw += dx * 0.006;
                camera.pitch = clamp(camera.pitch - dy * 0.006, -1.35, 1.35);
                repaint();
            }
        });
        Timer timer = new Timer(16, e -> tick());
        timer.start();
    }

    void setSelectionListener(Runnable listener) {
        this.selectionListener = listener == null ? () -> {
        } : listener;
    }

    TransformMode getTransformMode() {
        return transformMode;
    }

    void setTransformMode(TransformMode transformMode) {
        this.transformMode = transformMode;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        rasterizer.resize(getWidth(), getHeight());
        BufferedImage image = rasterizer.render(scene, camera);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.drawImage(image, 0, 0, null);
        g2.dispose();
    }

    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - lastNanos) / 1_000_000_000.0);
        lastNanos = now;
        moveCamera(dt);
        transformSelected(dt);
        scene.updateLight(dt);
        repaint();
    }

    private void handleToggleKey(int key) {
        if (key >= KeyEvent.VK_1 && key <= KeyEvent.VK_9) {
            int index = key - KeyEvent.VK_1;
            if (index < scene.entities.size()) {
                scene.selectedIndex = index;
                selectionListener.run();
            }
            return;
        }
        switch (key) {
            case KeyEvent.VK_T -> transformMode = TransformMode.TRANSLATE;
            case KeyEvent.VK_R -> transformMode = TransformMode.ROTATE;
            case KeyEvent.VK_Y -> transformMode = TransformMode.SCALE;
            case KeyEvent.VK_P -> scene.perspective = !scene.perspective;
            case KeyEvent.VK_M -> scene.filled = !scene.filled;
            case KeyEvent.VK_U -> {
                if (!scene.selected().lightMarker) {
                    scene.selected().textureEnabled = !scene.selected().textureEnabled;
                }
            }
            case KeyEvent.VK_C -> scene.cycleLightColor();
            case KeyEvent.VK_SPACE -> scene.lightAnimation = !scene.lightAnimation;
            default -> {
            }
        }
        selectionListener.run();
    }

    private void moveCamera(double dt) {
        double speed = pressed.contains(KeyEvent.VK_SHIFT) ? 5.0 : 2.6;
        Vec3 movement = Vec3.ZERO;
        if (pressed.contains(KeyEvent.VK_W)) {
            movement = movement.add(camera.forwardFlat());
        }
        if (pressed.contains(KeyEvent.VK_S)) {
            movement = movement.sub(camera.forwardFlat());
        }
        if (pressed.contains(KeyEvent.VK_D)) {
            movement = movement.add(camera.rightFlat());
        }
        if (pressed.contains(KeyEvent.VK_A)) {
            movement = movement.sub(camera.rightFlat());
        }
        if (pressed.contains(KeyEvent.VK_E)) {
            movement = movement.add(new Vec3(0, 1, 0));
        }
        if (pressed.contains(KeyEvent.VK_Q)) {
            movement = movement.sub(new Vec3(0, 1, 0));
        }
        if (movement.length() > 0.0) {
            camera.position = camera.position.add(movement.normalize().mul(speed * dt));
        }
    }

    private void transformSelected(double dt) {
        Entity entity = scene.selected();
        double directionX = 0.0;
        double directionY = 0.0;
        double directionZ = 0.0;
        if (pressed.contains(KeyEvent.VK_LEFT)) {
            directionX -= 1.0;
        }
        if (pressed.contains(KeyEvent.VK_RIGHT)) {
            directionX += 1.0;
        }
        if (pressed.contains(KeyEvent.VK_UP)) {
            directionY += 1.0;
        }
        if (pressed.contains(KeyEvent.VK_DOWN)) {
            directionY -= 1.0;
        }
        if (pressed.contains(KeyEvent.VK_PAGE_UP)) {
            directionZ += 1.0;
        }
        if (pressed.contains(KeyEvent.VK_PAGE_DOWN)) {
            directionZ -= 1.0;
        }
        if (directionX == 0.0 && directionY == 0.0 && directionZ == 0.0) {
            return;
        }

        switch (transformMode) {
            case TRANSLATE -> {
                double step = 1.25 * dt;
                entity.transform.position = entity.transform.position.add(new Vec3(directionX, directionY, directionZ).mul(step));
            }
            case ROTATE -> {
                double step = 1.8 * dt;
                entity.transform.rotation = entity.transform.rotation.add(new Vec3(directionY * step, directionX * step, directionZ * step));
            }
            case SCALE -> {
                double grow = (directionX + directionY + directionZ) * dt;
                entity.transform.scale = clamp(entity.transform.scale * (1.0 + grow), 0.18, 3.4);
            }
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
