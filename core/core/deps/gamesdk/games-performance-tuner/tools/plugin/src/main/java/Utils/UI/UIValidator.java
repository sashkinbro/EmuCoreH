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

package Utils.UI;

import static com.intellij.openapi.ui.cellvalidators.ValidatingTableCellRendererWrapper.CELL_VALIDATION_PROPERTY;

import View.Decorator.RoundedCornerBorder;
import View.Decorator.RoundedCornerBorder.BorderType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JSlider;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.border.Border;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.TableModel;
import org.jetbrains.annotations.NotNull;

public class UIValidator {

  public static final String ILLEGAL_TEXT_PATTERN = "[{|}]";
  public static final String VALIDATION_METHOD = "ComponentValidation.Method";
  public static final String ORIGINAL_BORDER = "Component.Border";

  public static void createTableValidator(Disposable disposable, JTable table,
      Supplier<ValidationInfo> validationMethod) {
    TableModelListener tableModelListener = new ComponentTableValidationUpdater(table,
        validationMethod);
    table.getModel().addTableModelListener(tableModelListener);
    Disposer
        .register(disposable, () -> table.getModel().removeTableModelListener(tableModelListener));
    createSimpleValidation(disposable, table, validationMethod);
  }

  public static void createSimpleValidation(Disposable disposable, JComponent jComponent,
      Supplier<ValidationInfo> validationMethod) {
    FocusListener focusListener = new ComponentFocusValidationUpdater(jComponent, validationMethod);
    jComponent.addFocusListener(focusListener);
    jComponent.putClientProperty(VALIDATION_METHOD, validationMethod);
    jComponent.putClientProperty(ORIGINAL_BORDER, jComponent.getBorder());
    Disposer.register(disposable, () -> jComponent.removeFocusListener(focusListener));
  }

  public static void createComboBoxValidation(Disposable disposable, JComboBox<?> jComponent,
      Supplier<ValidationInfo> validationMethod) {
    createSimpleValidation(disposable, jComponent, validationMethod);
    ActionListener actionListener = new ComponentActionValidationUpdater(jComponent,
        validationMethod);
    jComponent.addActionListener(actionListener);
    Disposer.register(disposable, () -> jComponent.removeActionListener(actionListener));
  }

  public static void createSliderValidator(Disposable disposable, JSlider jSlider,
      Supplier<ValidationInfo> validationMethod) {
    ChangeListener changeListener = new ComponentSliderValidationUpdate(jSlider, validationMethod);
    jSlider.addChangeListener(changeListener);
    Disposer.register(disposable, () -> jSlider.removeChangeListener(changeListener));
    createSimpleValidation(disposable, jSlider, validationMethod);
  }

  public static void createTextValidator(Disposable disposable, JTextField jTextField,
      Supplier<ValidationInfo> validationMethod) {
    DocumentAdapter documentAdapter = new ComponentTextValidationUpdater(jTextField,
        validationMethod);
    jTextField.getDocument().addDocumentListener(documentAdapter);
    Disposer.register(disposable,
        () -> jTextField.getDocument().removeDocumentListener(documentAdapter));
    jTextField.putClientProperty(VALIDATION_METHOD, validationMethod);
  }

  private static ValidationInfo hasValidationInfo(JComponent component) {
    return component != null ? (ValidationInfo) component
        .getClientProperty(CELL_VALIDATION_PROPERTY) : null;
  }

  private static Supplier<ValidationInfo> getValidationMethod(JComponent component) {
    return component != null ? (Supplier<ValidationInfo>) component
        .getClientProperty(VALIDATION_METHOD) : null;
  }

  public static boolean isComponentValid(JTextField jComponent) {
    if (getValidationMethod(jComponent) != null) {
      updateValidationRoundedBorder(getValidationMethod(jComponent), jComponent);
    }
    ValidationInfo cellInfo = UIValidator.hasValidationInfo(jComponent);
    return cellInfo == null || cellInfo.warning;
  }

  public static boolean isComponentValid(JComponent jComponent) {
    if (getValidationMethod(jComponent) != null) {
      updateValidationLineBorder(getValidationMethod(jComponent), jComponent);
    }
    ValidationInfo cellInfo = UIValidator.hasValidationInfo(jComponent);
    return cellInfo == null || cellInfo.warning;
  }

  public static boolean isTableCellsValid(JTable jTable) {
    TableModel tableModel = jTable.getModel();
    for (int i = 0; i < jTable.getRowCount(); i++) {
      for (int j = 0; j < jTable.getColumnCount(); j++) {
        JComponent component = (JComponent) jTable
            .getCellRenderer(i, j).getTableCellRendererComponent(jTable,
                tableModel.getValueAt(i, j), false, false, i, 0);
        if (!isComponentValid(component)) {
          return false;
        }
      }
    }
    return true;
  }

  public static boolean isTableCellsValidDeep(JTable jTable) {
    TableModel tableModel = jTable.getModel();
    for (int i = 0; i < jTable.getRowCount(); i++) {
      for (int j = 0; j < jTable.getColumnCount(); j++) {
        JComponent component = (JComponent) jTable
            .getCellRenderer(i, j).getTableCellRendererComponent(jTable,
                tableModel.getValueAt(i, j), false, false, i, 0);
        if (!isComponentValid(component)) {
          return false;
        }
        for (Component childComp : component.getComponents()) {
          if (childComp instanceof JComponent && !isComponentValid((JComponent) childComp)) {
            return false;
          }
        }
      }
    }
    return true;
  }

  private static void updateValidationLineBorder(
      Supplier<ValidationInfo> validationMethod,
      JComponent jComponent) {
    ValidationInfo cellInfo = validationMethod.get();
    jComponent.putClientProperty(CELL_VALIDATION_PROPERTY, cellInfo);
    if (cellInfo != null) {
      jComponent.setToolTipText(cellInfo.message);
      if (cellInfo.warning) {
        jComponent.setBorder(BorderFactory.createLineBorder(JBColor.YELLOW));
      } else {
        jComponent.setBorder(BorderFactory.createLineBorder(JBColor.RED));
      }
      validationMethod.get();
      return;
    }
    jComponent.setToolTipText("");
    jComponent.setBorder((Border) jComponent.getClientProperty(ORIGINAL_BORDER));
  }

  private static void updateValidationRoundedBorder(
      Supplier<ValidationInfo> validationMethod,
      JTextField jComponent) {
    ValidationInfo cellInfo = validationMethod.get();
    jComponent.putClientProperty(CELL_VALIDATION_PROPERTY, cellInfo);
    if (cellInfo != null) {
      jComponent.setToolTipText(cellInfo.message);
      if (cellInfo.warning) {
        jComponent.setBorder(new RoundedCornerBorder(BorderType.WARNING));
      } else {
        jComponent.setBorder(new RoundedCornerBorder(BorderType.ERROR));
      }
      validationMethod.get();
      return;
    }
    jComponent.setToolTipText("");
    jComponent.setBorder(new RoundedCornerBorder(BorderType.NORMAL));
  }


  private final static class ComponentFocusValidationUpdater implements FocusListener {

    private final JComponent jComponent;
    private final Supplier<ValidationInfo> validationMethod;

    public ComponentFocusValidationUpdater(JComponent jComponent,
        Supplier<ValidationInfo> supplier) {
      this.jComponent = jComponent;
      this.validationMethod = supplier;
    }

    @Override
    public void focusGained(FocusEvent e) {
      updateValidationLineBorder(validationMethod, jComponent);
    }

    @Override
    public void focusLost(FocusEvent e) {
      updateValidationLineBorder(validationMethod, jComponent);
    }
  }

  private final static class ComponentActionValidationUpdater implements ActionListener {

    private final JComponent jComponent;
    private final Supplier<ValidationInfo> validationMethod;

    public ComponentActionValidationUpdater(JComponent jComponent,
        Supplier<ValidationInfo> supplier) {
      this.jComponent = jComponent;
      this.validationMethod = supplier;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      updateValidationLineBorder(validationMethod, jComponent);
    }
  }

  private final static class ComponentTextValidationUpdater extends DocumentAdapter {

    private final JTextField jComponent;
    private final Supplier<ValidationInfo> validationMethod;

    public ComponentTextValidationUpdater(JTextField jComponent,
        Supplier<ValidationInfo> supplier) {
      this.jComponent = jComponent;
      this.validationMethod = supplier;
    }

    @Override
    protected void textChanged(@NotNull DocumentEvent documentEvent) {
      updateValidationRoundedBorder(validationMethod, jComponent);
    }
  }

  private final static class ComponentTableValidationUpdater implements TableModelListener {

    private final JComponent jComponent;
    private final Supplier<ValidationInfo> validationMethod;

    public ComponentTableValidationUpdater(JComponent jComponent,
        Supplier<ValidationInfo> supplier) {
      this.jComponent = jComponent;
      this.validationMethod = supplier;
    }

    @Override
    public void tableChanged(TableModelEvent e) {
      updateValidationLineBorder(validationMethod, jComponent);
    }
  }

  private final static class ComponentSliderValidationUpdate implements ChangeListener {

    private final JSlider component;
    private final Supplier<ValidationInfo> validationMethod;

    public ComponentSliderValidationUpdate(JSlider component,
        Supplier<ValidationInfo> supplier) {
      this.component = component;
      this.validationMethod = supplier;
    }

    @Override
    public void stateChanged(ChangeEvent e) {
      if (!component.getValueIsAdjusting()) {
        updateValidationLineBorder(validationMethod, component);
      }
    }
  }
}
