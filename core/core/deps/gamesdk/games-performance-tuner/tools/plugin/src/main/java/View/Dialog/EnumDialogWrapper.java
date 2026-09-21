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
package View.Dialog;

import static Utils.UI.UIValidator.ILLEGAL_TEXT_PATTERN;

import Controller.Enum.EnumController;
import Model.EnumDataModel;
import Utils.Resources.ResourceLoader;
import Utils.UI.UIValidator;
import View.Decorator.RoundedCornerBorder;
import View.Decorator.RoundedCornerBorder.BorderType;
import View.Decorator.TableRenderer;
import View.Decorator.TableRenderer.RoundedCornerRenderer;
import View.TabLayout;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.table.JBTable;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Collections;
import java.util.regex.Pattern;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import org.jdesktop.swingx.HorizontalLayout;
import org.jdesktop.swingx.VerticalLayout;
import org.jdesktop.swingx.prompt.PromptSupport;
import org.jdesktop.swingx.prompt.PromptSupport.FocusBehavior;
import org.jetbrains.annotations.Nullable;

public class EnumDialogWrapper extends DialogWrapper {


  private EnumLayout enumLayout;
  private final EnumController controller;
  private boolean isEdit;
  private int editingRow;
  private EnumDataModel enumDataModel;
  private final ResourceLoader resourceLoader = ResourceLoader.getInstance();

  public EnumDialogWrapper(EnumController controller) {
    super(true);
    setTitle(resourceLoader.get("add_enum_title"));
    this.controller = controller;
    init();
  }

  public EnumDialogWrapper(EnumController controller, int row, EnumDataModel enumDataModel) {
    super(true);
    setTitle(resourceLoader.get("edit_enum_title"));
    this.controller = controller;
    this.isEdit = true;
    this.editingRow = row;
    this.enumDataModel = enumDataModel;
    init();
  }

  private void stopCellEditingMomentarily() {
    if (!enumLayout.optionsTable.isEditing()) {
      return;
    }
    enumLayout.optionsTable.getCellEditor().stopCellEditing();
  }


  @Override
  protected void doOKAction() {
    stopCellEditingMomentarily();
    if (!enumLayout.isViewValid()) {
      Messages.showErrorDialog(resourceLoader.get("fix_errors_first"),
          resourceLoader.get("unable_to_close_title"));
      return;
    }
    if (isEdit) {
      if (controller.editEnum(editingRow, enumLayout.getName(), enumLayout.getOptions())) {
        super.doOKAction();
      } else {
        Messages.showInfoMessage(
            String.format(resourceLoader.get("enum_name_already_exist"), enumLayout.getName()),
            resourceLoader.get("unable_to_close_title"));
      }
    } else {
      if (controller.addEnum(enumLayout.getName(), enumLayout.getOptions())) {
        super.doOKAction();
      } else {
        Messages.showInfoMessage(
            String.format(resourceLoader.get("enum_name_already_exist"), enumLayout.getName()),
            resourceLoader.get("unable_to_close_title"));
      }
    }
  }

  @Override
  protected @Nullable
  JComponent createCenterPanel() {
    enumLayout = new EnumLayout(getDisposable());
    if (isEdit) {
      enumLayout.setData(enumDataModel);
    }
    return enumLayout;
  }

  private static final class EnumLayout extends TabLayout {

    private JTable optionsTable;
    private JPanel decoratorPanel;
    private JTextField nameTextField;
    private DefaultTableModel model;
    private final Disposable disposable;
    private final ResourceLoader resourceLoader = ResourceLoader.getInstance();

    EnumLayout(Disposable disposable) {
      this.disposable = disposable;
      initComponents();
      addComponents();
      initValidators();
    }

    private void addComponents() {
      JPanel namePanel = new JPanel(new HorizontalLayout());
      namePanel.add(nameTextField);
      PromptSupport.setPrompt(resourceLoader.get("label_name"), nameTextField);
      PromptSupport.setFocusBehavior(FocusBehavior.HIDE_PROMPT, nameTextField);
      this.add(namePanel);
      this.add(Box.createVerticalStrut(20));
      this.add(decoratorPanel);
    }

    public void setData(EnumDataModel enumDataModel) {
      nameTextField.setText(enumDataModel.getName());
      enumDataModel.getOptions().forEach(optionName -> model.addRow(new String[]{optionName}));
    }

    public String getName() {
      return nameTextField.getText();
    }

    public ArrayList<String> getOptions() {
      ArrayList<String> options = new ArrayList<>();
      int rows = optionsTable.getModel().getRowCount();

      for (int i = 0; i < rows; i++) {
        options.add((String) optionsTable.getModel().getValueAt(i, 0));
      }

      return options;
    }

    private void initComponents() {
      this.setLayout(new VerticalLayout());
      nameTextField = new JTextField("", 30);
      model =
          new DefaultTableModel() {
            @Override
            public void removeRow(int row) {
              dataVector.remove(row);
              fireTableDataChanged();
            }
          };
      optionsTable = new JBTable();
      decoratorPanel =
          ToolbarDecorator.createDecorator(optionsTable)
              .setAddAction(it -> model.addRow(new String[]{""}))
              .setRemoveAction(
                  it -> {
                    int currentRow = optionsTable.getSelectedRow();
                    if (optionsTable.isEditing()) {
                      optionsTable.getCellEditor().stopCellEditing();
                    }
                    model.removeRow(currentRow);
                  })
              .createPanel();
      setDecoratorPanelSize(decoratorPanel);
      optionsTable.setFillsViewportHeight(true);
      optionsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      optionsTable.getTableHeader().setReorderingAllowed(false);
      optionsTable.setRowSelectionAllowed(true);
      optionsTable.setSelectionForeground(null);
      optionsTable.setSelectionBackground(null);
      optionsTable.setIntercellSpacing(new Dimension(0, 0));
      model.setColumnIdentifiers(new String[]{resourceLoader.get("table_column_options")});
      optionsTable.setModel(model);
      nameTextField.setBorder(new RoundedCornerBorder(BorderType.NORMAL));
    }

    private void initValidators() {
      UIValidator.createTextValidator(disposable, nameTextField, () -> {
        String name = nameTextField.getText();
        if (name.isEmpty()) {
          return new ValidationInfo(resourceLoader.get("field_empty_error"),
              nameTextField);
        }
        if (Pattern.compile(ILLEGAL_TEXT_PATTERN).matcher(name).find()) {
          return new ValidationInfo(
              String.format(resourceLoader.get("field_illegal_character_error"), name),
              nameTextField);
        }
        return null;
      });

      UIValidator.createTableValidator(disposable, optionsTable,
          () -> {
            if (optionsTable.getRowCount() == 0) {
              return new ValidationInfo(resourceLoader.get("options_table_empty_error"),
                  optionsTable);
            }
            ArrayList<String> options = getOptions();
            boolean isOptionDuplicate =
                options.stream().anyMatch(option -> Collections.frequency(options, option) > 1);
            if (isOptionDuplicate) {
              return new ValidationInfo(resourceLoader.get("repeated_fields_error"),
                  optionsTable);
            }
            return null;
          });
      optionsTable.getColumnModel()
          .getColumn(0).setCellEditor(TabLayout.getTextFieldModel());

      //noinspection UnstableApiUsage
      optionsTable.getColumnModel().getColumn(0)
          .setCellRenderer(
              TableRenderer.getRendererTextBoxWithValidation(new RoundedCornerRenderer(),
                  (value, row, column) -> {
                    String strVal = value.toString();
                    if (strVal.isEmpty()) {
                      return new ValidationInfo(resourceLoader.get("field_empty_error"));
                    }
                    if (Pattern.compile(ILLEGAL_TEXT_PATTERN).matcher(strVal).find()) {
                      return new ValidationInfo(
                          String
                              .format(resourceLoader.get("field_illegal_character_error"), strVal));
                    }
                    return null;
                  }));

    }

    public boolean isViewValid() {
      return isTableCellsValid() & isNameTextFieldValid() & isTableValid();
    }

    private boolean isTableCellsValid() {
      return UIValidator.isTableCellsValid(optionsTable);
    }

    private boolean isNameTextFieldValid() {
      return UIValidator.isComponentValid(nameTextField);
    }

    private boolean isTableValid() {
      return UIValidator.isComponentValid(optionsTable);
    }
  }
}