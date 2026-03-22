package engine.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * Represents variantu FlowLayoutu, která správně vrací výšku po zalomení řádků.
 */
public class WrapLayout extends FlowLayout {

    public WrapLayout() {
        super();
    }

    public WrapLayout(int align) {
        super(align);
    }

    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension minimum = layoutSize(target, false);
        minimum.width -= getHgap() + 1;
        return minimum;
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int targetWidth = target.getSize().width;
            Container container = target;
            while (targetWidth <= 0 && container.getParent() != null) {
                container = container.getParent();
                targetWidth = container.getSize().width;
            }
            if (targetWidth <= 0) {
                targetWidth = Integer.MAX_VALUE;
            }

            Insets insets = target.getInsets();
            int horizontalInsetsAndGap = insets.left + insets.right + getHgap() * 2;
            int maxWidth = Math.max(0, targetWidth - horizontalInsetsAndGap);

            Dimension dimension = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;

            for (Component component : target.getComponents()) {
                if (!component.isVisible()) {
                    continue;
                }

                Dimension componentSize = preferred
                        ? component.getPreferredSize()
                        : component.getMinimumSize();

                if (rowWidth > 0 && rowWidth + getHgap() + componentSize.width > maxWidth) {
                    addRow(dimension, rowWidth, rowHeight);
                    rowWidth = 0;
                    rowHeight = 0;
                }

                if (rowWidth > 0) {
                    rowWidth += getHgap();
                }
                rowWidth += componentSize.width;
                rowHeight = Math.max(rowHeight, componentSize.height);
            }

            addRow(dimension, rowWidth, rowHeight);

            dimension.width += horizontalInsetsAndGap;
            dimension.height += insets.top + insets.bottom + getVgap() * 2;

            if (SwingUtilities.getAncestorOfClass(JScrollPane.class, target) != null) {
                dimension.width -= getHgap() + 1;
            }

            return dimension;
        }
    }

    private void addRow(Dimension dimension, int rowWidth, int rowHeight) {
        dimension.width = Math.max(dimension.width, rowWidth);
        if (dimension.height > 0) {
            dimension.height += getVgap();
        }
        dimension.height += rowHeight;
    }
}
