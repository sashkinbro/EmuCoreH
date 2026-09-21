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

package View.Fidelity;

import View.Decorator.RoundedCornerBorder;
import com.intellij.util.ui.UIUtil;
import java.awt.BorderLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/*
This class is responsible for just enclosing a TextField and label(error label) inside a Jpanel
for validation. As using the normal validation icon place would look glitchy.

Only meant to be used with fidelity panel.
 */
public class FidelityValidatablePanelWithTextField extends JPanel {

  private JTextField jTextField;
  private JLabel errorLabel;

  public FidelityValidatablePanelWithTextField() {
    jTextField = new JTextField();
    errorLabel = new JLabel();
    setLayout(new BorderLayout(0, 0));
    add(jTextField, BorderLayout.CENTER);
    add(errorLabel, BorderLayout.EAST);
    setBorder(new RoundedCornerBorder());
    setBackground(UIUtil.getTableBackground());
    jTextField.setBorder(null);
    jTextField.setBackground(UIUtil.getTableBackground());
  }

  public JLabel getErrorLabel() {
    return errorLabel;
  }

  public JTextField getTextField() {
    return jTextField;
  }

}
