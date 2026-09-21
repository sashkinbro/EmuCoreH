/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package View.Decorator;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.border.AbstractBorder;

public final class RoundedCornerBorder extends AbstractBorder {

  public enum BorderType {
    NORMAL(JBColor.GRAY),
    WARNING(JBColor.YELLOW),
    ERROR(JBColor.RED);
    JBColor borderColor;

    private BorderType(JBColor borderColor) {
      this.borderColor = borderColor;
    }

    public JBColor getBorderColor() {
      return borderColor;
    }
  }

  private final Color ALPHA_ZERO = UIUtil.getTableBackground();
  BorderType borderType = BorderType.NORMAL;

  public RoundedCornerBorder() {
  }

  public RoundedCornerBorder(BorderType borderType) {
    this.borderType = borderType;
  }

  @Override
  public void paintBorder(
      Component component, Graphics graphics, int x, int y, int width, int height) {
    Graphics2D graphics2D = (Graphics2D) graphics.create();
    graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    Shape border = getBorderShape(x, y, width - 1, height - 1);
    Color backgroundColor = component.getParent() != null ? component.getParent().getBackground() :
        ALPHA_ZERO;
    graphics2D.setPaint(backgroundColor);
    Area corner = new Area(new Rectangle2D.Double(x, y, width, height));
    corner.subtract(new Area(border));
    graphics2D.fill(corner);
    graphics2D.setPaint(borderType.getBorderColor());
    graphics2D.draw(border);
    graphics2D.dispose();
  }

  public Shape getBorderShape(int x, int y, int width, int height) {
    int arcWidth = height;
    int arcHeight = height;
    return new RoundRectangle2D.Double(x, y, width, height, arcWidth, arcHeight);
  }

  @Override
  public Insets getBorderInsets(Component component) {
    return JBUI.insets(4, 8);
  }

  @Override
  public Insets getBorderInsets(Component component, Insets insets) {
    insets.set(4, 8, 4, 8);
    return insets;
  }
}

