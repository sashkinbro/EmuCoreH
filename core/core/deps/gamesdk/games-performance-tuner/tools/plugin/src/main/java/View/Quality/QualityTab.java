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

package View.Quality;

import Controller.Quality.QualityTabController;
import Controller.Quality.QualityTableModel;
import Utils.Resources.ResourceLoader;
import Utils.UI.UIValidator;
import Utils.Validation.ValidationTool;
import View.Decorator.TableRenderer;
import View.Decorator.TableRenderer.RoundedCornerRenderer;
import View.Fidelity.FieldType;
import View.Quality.QualityDecorators.EnumOptionsDecorator;
import View.Quality.QualityDecorators.HeaderCenterLabel;
import View.Quality.QualityDecorators.ParameterNameRenderer;
import View.Quality.QualityDecorators.TrendRenderer;
import View.TabLayout;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.ui.cellvalidators.TableCellValidator;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.UIUtil;
import java.awt.Dimension;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.OptionalInt;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import org.jdesktop.swingx.VerticalLayout;
import org.jetbrains.annotations.NotNull;

public class QualityTab extends TabLayout implements PropertyChangeListener {

  private final static ResourceLoader RESOURCE_LOADER = ResourceLoader.getInstance();
  private final JLabel title;
  private final JLabel aboutQualitySettings;

  private JBTable qualityParametersTable;
  private QualityTableModel qualityTableModel;
  private final QualityTabController qualityTabController;
  private JPanel decoratorPanel;

  private final Disposable disposable;

  public QualityTab(QualityTabController qualityTabController, Disposable disposable) {
    this.setLayout(new VerticalLayout());
    this.qualityTabController = qualityTabController;
    this.disposable = disposable;
    title = new JLabel(RESOURCE_LOADER.get("quality_levels"));
    aboutQualitySettings = new JLabel(RESOURCE_LOADER.get("quality_info"));
    setSize();
    initComponents();
    addComponents();
    initValidators();
  }

  private void initValidators() {
    UIValidator.createTableValidator(disposable, qualityParametersTable, () ->
    {
      if (qualityTabController.getDefaultQuality() == -1) {
        return new ValidationInfo(RESOURCE_LOADER.get("default_quality_error"));
      }
      return null;
    });
  }

  public QualityTabController getQualityTabController() {
    return qualityTabController;
  }

  private void addComponents() {
    this.add(title);
    this.add(aboutQualitySettings);
    this.add(decoratorPanel);
  }

  private void initComponents() {

    title.setFont(getMainFont());
    aboutQualitySettings.setFont(getSecondaryFont());

    qualityTableModel = new QualityTableModel(qualityTabController);
    qualityParametersTable = new JBTable(qualityTableModel) {
      @Override
      public JTableHeader getTableHeader() {
        JTableHeader jTableHeader = super.getTableHeader();
        setColumnsSize(jTableHeader);
        return jTableHeader;
      }

      @Override
      public TableCellRenderer getCellRenderer(int row, int column) {
        if (column == 0) {
          return new ParameterNameRenderer();
        } else if (column == 1) {
          return new TrendRenderer();
        } else {
          if (qualityTabController.isEnum(row)) {
            return new EnumOptionsDecorator(qualityTabController.getEnumOptionsByIndex(row));
          } else {
            return TableRenderer.getRendererTextBoxWithValidation(new RoundedCornerRenderer(),
                new NumberTextFieldValidator());
          }
        }

      }

      @Override
      public TableCellEditor getCellEditor(int row, int column) {
        if (column <= 1) {
          return getTextFieldModel();
        } else {
          if (qualityTabController.isEnum(row)) {
            return new EnumOptionsDecorator(qualityTabController.getEnumOptionsByIndex(row));
          } else {
            return getIntegerTextFieldModel();
          }
        }
      }
    };
    qualityTabController.addInitialQuality(qualityParametersTable);
    decoratorPanel = ToolbarDecorator.createDecorator(qualityParametersTable)
        .setAddAction(anActionButton -> qualityTabController.addColumn(qualityParametersTable))
        .setRemoveAction(
            anActionButton -> qualityTabController.removeColumn(qualityParametersTable))
        .setRemoveActionUpdater(e -> qualityParametersTable.getSelectedColumn() > 1)
        .setMinimumSize(new Dimension(600, 500))
        .addExtraAction(new ChangeButton())
        .createPanel();

    setDecoratorPanelSize(decoratorPanel);
    setTableVisuals();
  }

  private void setTableVisuals() {
    qualityParametersTable.getTableHeader().setDefaultRenderer(new HeaderCenterLabel());
    qualityParametersTable.getTableHeader().setReorderingAllowed(false);
    qualityParametersTable.setRowSelectionAllowed(false);
    qualityParametersTable.setCellSelectionEnabled(false);
    qualityParametersTable.setColumnSelectionAllowed(true);
    qualityParametersTable.setSelectionBackground(UIUtil.getTableBackground());
    qualityParametersTable.setSelectionForeground(UIUtil.getTableForeground());
    qualityParametersTable.setIntercellSpacing(new Dimension(0, 0));
    qualityParametersTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    qualityParametersTable.setRowHeight(25);
    qualityParametersTable.setShowGrid(false);
    setColumnsSize(qualityParametersTable.getTableHeader());
  }

  private void setColumnsSize(JTableHeader jTableHeader) {
    TableColumn parameterNameCol = jTableHeader.getColumnModel().getColumn(0);
    parameterNameCol.setMaxWidth(140);
    parameterNameCol.setMinWidth(140);
    parameterNameCol.setPreferredWidth(140);
    TableColumn trendCol = jTableHeader.getColumnModel().getColumn(1);
    trendCol.setPreferredWidth(80);
    trendCol.setMaxWidth(80);
    trendCol.setMinWidth(80);
    for (int i = 2; i < jTableHeader.getColumnModel().getColumnCount(); i++) {
      TableColumn dataColumn = jTableHeader.getColumnModel().getColumn(i);
      dataColumn.setMinWidth(140);
      dataColumn.setMaxWidth(140);
      dataColumn.setPreferredWidth(140);
    }
  }

  @Override
  public void propertyChange(PropertyChangeEvent evt) {
    switch (evt.getPropertyName()) {
      case "addField":
        qualityTabController.addRow(qualityParametersTable);
        break;
      case "nameChange":
        int fieldRow = (int) ((Object[]) evt.getNewValue())[0];
        String newName = ((Object[]) evt.getNewValue())[1].toString();
        qualityTableModel.setValueAt(newName, fieldRow, 0);
        break;
      case "removeField":
        int row = Integer.parseInt(evt.getNewValue().toString());
        qualityTabController.removeRow(qualityParametersTable, row);
        qualityTabController.removeFieldRowData(row);
        break;
      case "typeChange":
        int index = (int) evt.getNewValue();
        qualityTableModel
            .setRowValue(index, qualityTabController.getDefaultValueByIndex(index));
        break;
      case "editOptions":
        OptionalInt currentIndex = qualityTabController.getFidelityRowByEnumName(
            ((Object[]) evt.getNewValue())[1].toString()
        );
        currentIndex.ifPresent(value -> qualityTableModel
            .setRowValue(value, qualityTabController.getDefaultValueByIndex(value)));

    }
  }


  public boolean isViewValid() {
    return UIValidator.isComponentValid(qualityParametersTable)
        && UIValidator.isTableCellsValid(qualityParametersTable);
  }

  public void saveSettings() {
    qualityParametersTable.clearSelection();
  }

  @SuppressWarnings("UnstableApiUsage")
  private final class NumberTextFieldValidator implements TableCellValidator {

    @Override
    public ValidationInfo validate(Object value, int row, int column) {
      FieldType fieldType = qualityTabController.getFieldTypeByRow(row);
      if (fieldType.equals(FieldType.INT32)) {
        return ValidationTool.getIntegerValueValidationInfo(value.toString());
      } else if (fieldType.equals(FieldType.FLOAT)) {
        return ValidationTool.getFloatValueValidationInfo(value.toString());
      } else {
        return null;
      }
    }
  }

  private final class ChangeButton extends AnActionButton {

    public ChangeButton() {
      super("Set Default Quality", AllIcons.Actions.SetDefault);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent anActionEvent) {
      qualityTabController.setDefaultQuality(qualityParametersTable);
    }

    @Override
    public void updateButton(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabled(qualityParametersTable.getSelectedColumn() >= 2);
    }
  }
}
