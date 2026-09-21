/*
 * Copyright (C) 2024 The Android Open Source Project
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
 * limitations under the License.
 *
 *
 * These are tests for GameTextInput. This tests emulate both software and
 * hardware keyboards and can on a real Android devices or an emulator.
 *
 * Espresso doesn't properly print reasons of failures. If tests fail,
 * run this to see real and expected values:
 *
 * adb logcat | grep -A 5 -B 5 "Expected:"
 */
package com.google.androidgamesdk.gametextinput.test;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.ViewInteraction;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.filters.LargeTest;
import com.google.androidgamesdk.gametextinput.InputConnection;
import com.google.androidgamesdk.gametextinput.State;
import com.google.common.collect.ImmutableList;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/**
 * GameTextInput Instrumented tests, which execute on an Android device.
 *
 * To speedup test time tests are combined to groups.
 */
@RunWith(Parameterized.class)
@LargeTest
public class InputTest {
  static private final String TAG = "InputTest";
  enum TestGroup {
    UNKNOWN,
    TYPE_TEXT,
    KEY_CHARACTER,
    KEY_DEL,
    KEY_FORWARD_DEL,
    KEY_DPAD_LEFT,
    KEY_DPAD_RIGHT,
    KEYCODE_MOVE_HOME,
    KEYCODE_MOVE_END
  }

  @Target({ElementType.METHOD})
  @Retention(RetentionPolicy.RUNTIME)
  public @interface TestToCombine {
    TestGroup group() default TestGroup.UNKNOWN;
  }

  public enum PressKeyImplementation { PRESS_KEY, ON_KEY_LISTENER }
  ;

  @Parameter(0) public PressKeyImplementation pressKeyImplementation;

  @Parameters(name = "{0}")
  public static ImmutableList<Object[]> parameters() {
    ImmutableList.Builder<Object[]> listBuilder = new ImmutableList.Builder<>();
    listBuilder.add(new Object[] {PressKeyImplementation.PRESS_KEY});
    listBuilder.add(new Object[] {PressKeyImplementation.ON_KEY_LISTENER});
    return listBuilder.build();
  }

  @Rule
  public ActivityScenarioRule<MainActivity> activityScenarioRule =
      new ActivityScenarioRule<>(MainActivity.class);

  @Before
  public void setUp() {
    onInputView().perform(activateSoftKeyboard());
  }

  @After
  public void tearDown() {
    onInputView().perform(deactivateSoftKeyboard());
  }

  @TestToCombine(group = TestGroup.TYPE_TEXT)
  public void typeText_singleCaracter() {
    onInputView().perform(typeText("c"));
    checkResultText("c");
  }

  @TestToCombine(group = TestGroup.TYPE_TEXT)
  public void typeText_phrase() {
    onInputView().perform(typeText("123 456"));
    checkResultText("123 456");
  }

  @TestToCombine(group = TestGroup.TYPE_TEXT)
  public void typeText_selectAll_overwrites() {
    onInputView().perform(typeText("abcdef"), selectText(0, 6), typeText("xyz"));
    checkResultText("xyz");
  }

  @TestToCombine(group = TestGroup.TYPE_TEXT)
  public void typeText_twice() {
    onInputView().perform(typeText("abc"), typeText("def"));
    checkResultText("abcdef");
  }

  @TestToCombine(group = TestGroup.KEY_CHARACTER)
  public void keyCharacter_single() {
    onInputView().perform(key(KeyEvent.KEYCODE_1));
    checkResultText("1");
  }

  @TestToCombine(group = TestGroup.KEY_CHARACTER)
  public void keyCharacter_sequence() {
    onInputView().perform(key(KeyEvent.KEYCODE_1), key(KeyEvent.KEYCODE_A), key(KeyEvent.KEYCODE_2),
        key(KeyEvent.KEYCODE_Z), key(KeyEvent.KEYCODE_9), key(KeyEvent.KEYCODE_R));
    checkResultText("1a2z9r");
  }

  @TestToCombine(group = TestGroup.KEY_CHARACTER)
  @Test(timeout = 100000)
  public void keyCharacter_replaceSelection() {
    onInputView().perform(
        setState("BeginSelectionEnd", 5, 14), key(KeyEvent.KEYCODE_1), key(KeyEvent.KEYCODE_2));
    checkResultText("Begin12End");
  }

  @TestToCombine(group = TestGroup.KEY_CHARACTER)
  public void keyCharacter_replaceSelectionAll() {
    onInputView().perform(key(KeyEvent.KEYCODE_A), key(KeyEvent.KEYCODE_B), selectText(0, 2),
        key(KeyEvent.KEYCODE_1), key(KeyEvent.KEYCODE_2));
    checkResultText("12");
  }

  @TestToCombine(group = TestGroup.KEY_DEL)
  public void keyDel_atTheEnd() {
    onInputView().perform(setState("abcdef", 6, 6), key(KeyEvent.KEYCODE_DEL));
    checkResultText("abcde");
  }

  @TestToCombine(group = TestGroup.KEY_DEL)
  public void keyDel_emoji() {
    onInputView().perform(setState("abc\uD83D\uDE0Dd", 5, 5), key(KeyEvent.KEYCODE_DEL));
    checkResultText("abcd");
  }

  @TestToCombine(group = TestGroup.KEY_DEL)
  public void keyDel_firstCharacter() {
    onInputView().perform(setState("abcdef", 1, 1), key(KeyEvent.KEYCODE_DEL));
    checkResultText("bcdef");
  }

  @TestToCombine(group = TestGroup.KEY_DEL)
  public void keyDel_middleTwoCharacters() {
    onInputView().perform(
        setState("123ab456", 5, 5), key(KeyEvent.KEYCODE_DEL), key(KeyEvent.KEYCODE_DEL));
    checkResultText("123456");
  }

  @TestToCombine(group = TestGroup.KEY_DEL)
  public void keyDel_selection() {
    onInputView().perform(setState("123sel456", 3, 6), key(KeyEvent.KEYCODE_DEL));
    checkResultText("123456");
  }

  @TestToCombine(group = TestGroup.KEY_DEL)
  public void keyDel_selectAll() {
    onInputView().perform(setState("sel", 0, 3), key(KeyEvent.KEYCODE_DEL));
    checkResultText("");
  }

  @TestToCombine(group = TestGroup.KEY_DEL)
  public void keyDel_atTheStart_noDeletion() {
    onInputView().perform(setState("abc", 0, 0), key(KeyEvent.KEYCODE_DEL));

    checkResultText("abc");
  }

  @TestToCombine(group = TestGroup.KEY_DEL)
  public void keyDel_selectionTwoCharacters() {
    onInputView().perform(setState("abcdefgh", 6, 8), key(KeyEvent.KEYCODE_DEL));
    checkResultText("abcdef");
  }

  @TestToCombine(group = TestGroup.KEY_DEL)
  public void keyDel_selectionFirstTwoCharacters() {
    onInputView().perform(setState("mncdef", 0, 2), key(KeyEvent.KEYCODE_DEL), typeText("ab"));
    checkResultText("abcdef");
  }

  @TestToCombine(group = TestGroup.KEY_DEL)
  public void keyDel_selectionMiddle() {
    onInputView().perform(setState("mncdef", 1, 4), key(KeyEvent.KEYCODE_DEL),
        key(KeyEvent.KEYCODE_X), key(KeyEvent.KEYCODE_Y));
    checkResultText("mxyef");
  }

  @TestToCombine(group = TestGroup.KEY_FORWARD_DEL)
  public void keyForwardDel_atTheEnd_noDeletion() {
    onInputView().perform(setState("abc", 3, 3), key(KeyEvent.KEYCODE_FORWARD_DEL));
    checkResultText("abc");
  }

  @TestToCombine(group = TestGroup.KEY_FORWARD_DEL)
  public void keyForwardDel_twice() {
    onInputView().perform(setState("abcdef", 3, 3), key(KeyEvent.KEYCODE_FORWARD_DEL),
        key(KeyEvent.KEYCODE_FORWARD_DEL));
    checkResultText("abcf");
  }

  @TestToCombine(group = TestGroup.KEY_FORWARD_DEL)
  public void keyForwardDel_emoji() {
    onInputView().perform(setState("abc\uD83D\uDE0Dd", 3, 3), key(KeyEvent.KEYCODE_FORWARD_DEL));
    checkResultText("abcd");
  }

  @TestToCombine(group = TestGroup.KEY_FORWARD_DEL)
  public void keyForwardDel_lastCharacter() {
    onInputView().perform(setState("abcdef", 5, 5), key(KeyEvent.KEYCODE_FORWARD_DEL));
    checkResultText("abcde");
  }

  @TestToCombine(group = TestGroup.KEY_FORWARD_DEL)
  public void keyForwardDel_selectAll() {
    onInputView().perform(setState("sel", 0, 3), key(KeyEvent.KEYCODE_FORWARD_DEL));
    checkResultText("");
  }

  @TestToCombine(group = TestGroup.KEY_FORWARD_DEL)
  public void keyForwardDel_selectionCases() {
    onInputView().perform(setState("abcdefgh", 6, 8), key(KeyEvent.KEYCODE_FORWARD_DEL));
    checkResultText("abcdef");

    onInputView().perform(selectText(0, 2), key(KeyEvent.KEYCODE_FORWARD_DEL), typeText("mn"));
    checkResultText("mncdef");

    onInputView().perform(selectText(1, 4), key(KeyEvent.KEYCODE_FORWARD_DEL), typeText("xyz"));
    checkResultText("mxyzef");
  }

  @TestToCombine(group = TestGroup.KEY_DPAD_LEFT)
  public void keyDpadLeft_middle() {
    onInputView().perform(
        setState("abcdefgh", 6, 6), key(KeyEvent.KEYCODE_DPAD_LEFT), key(KeyEvent.KEYCODE_X));

    checkResultText("abcdexfgh");
  }

  @TestToCombine(group = TestGroup.KEY_DPAD_RIGHT)
  public void keyDpadLeft_emoji() {
    onInputView().perform(setState("abc\uD83D\uDE0Dd", 5, 5), key(KeyEvent.KEYCODE_DPAD_LEFT),
        key(KeyEvent.KEYCODE_1));
    checkResultText("abc1\uD83D\uDE0Dd");
  }

  @TestToCombine(group = TestGroup.KEY_DPAD_RIGHT)
  public void keyDpadRight_middle() {
    onInputView().perform(
        setState("abcdefgh", 6, 6), key(KeyEvent.KEYCODE_DPAD_RIGHT), key(KeyEvent.KEYCODE_X));

    checkResultText("abcdefgxh");
  }

  @TestToCombine(group = TestGroup.KEY_DPAD_RIGHT)
  public void keyDpadRight_emoji() {
    onInputView().perform(setState("abc\uD83D\uDE0Dd", 3, 3), key(KeyEvent.KEYCODE_DPAD_RIGHT),
        key(KeyEvent.KEYCODE_1));
    checkResultText("abc\uD83D\uDE0D1d");
  }

  @TestToCombine(group = TestGroup.KEYCODE_MOVE_HOME)
  public void keyMoveHome_fromMiddle() {
    onInputView().perform(setState("abcdefgh", 3, 3), key(KeyEvent.KEYCODE_1),
        key(KeyEvent.KEYCODE_MOVE_HOME), key(KeyEvent.KEYCODE_2));
    checkResultText("2abc1defgh");
  }

  @TestToCombine(group = TestGroup.KEYCODE_MOVE_END)
  public void keyMoveEnd_fromFirstChar() {
    onInputView().perform(setState("abcdefgh", 0, 0), key(KeyEvent.KEYCODE_1),
        key(KeyEvent.KEYCODE_MOVE_END), key(KeyEvent.KEYCODE_2));
    checkResultText("1abcdefgh2");
  }

  @Test
  public void runCombinedTests_type() {
    runGroupTests(TestGroup.TYPE_TEXT, this.getClass());
    runGroupTests(TestGroup.KEY_CHARACTER, this.getClass());
  }

  @Test
  public void runCombinedTests_DeletionKeys() {
    runGroupTests(TestGroup.KEY_DEL, this.getClass());
    runGroupTests(TestGroup.KEY_FORWARD_DEL, this.getClass());
  }

  @Test
  public void runCombinedTests_DpadKeys() {
    runGroupTests(TestGroup.KEY_DPAD_LEFT, this.getClass());
    runGroupTests(TestGroup.KEY_DPAD_RIGHT, this.getClass());
    runGroupTests(TestGroup.KEYCODE_MOVE_HOME, this.getClass());
    runGroupTests(TestGroup.KEYCODE_MOVE_END, this.getClass());
  }

  private ViewInteraction onInputView() {
    return onView(withId(R.id.input_enabled_text_view));
  }

  private void resetBeforeTest() {
    onInputView().perform(setState("", 0, 0));
  }

  private void checkResultText(String text) {
    onInputView().perform(waitForIdle());
    onView(withId(R.id.displayed_text)).check(matches(withText(text)));
  }

  private ViewAction key(int keyCode) {
    if (pressKeyImplementation == PressKeyImplementation.PRESS_KEY) {
      return pressKey(keyCode);
    }
    if (pressKeyImplementation == PressKeyImplementation.ON_KEY_LISTENER) {
      return passKeyToOnKeyListener(keyCode);
    }
    return null;
  }

  // Espresso doesn't support selecting text, so this action implements this ability.
  // Note that start = end means in Android this is just a text cursor.
  // For example, start = end = 3 means that there is no selection and the cursor
  // is at position 3.
  private ViewAction selectText(int start, int end) {
    return new ViewAction() {
      @Override
      public Matcher<View> getConstraints() {
        return isDisplayed();
      }

      @Override
      public String getDescription() {
        return "Select text";
      }

      @Override
      public void perform(UiController uiController, View view) {
        InputEnabledTextView inputView = (InputEnabledTextView) view;
        State state = inputView.getState();
        state.selectionStart = start;
        state.selectionEnd = end;

        InputConnection ic = inputView.getInputConnection();
        ic.setState(state);
        uiController.loopMainThreadForAtLeast(500);
      }
    };
  }

  private ViewAction sleep(int time) {
    return new ViewAction() {
      @Override
      public Matcher<View> getConstraints() {
        return isDisplayed();
      }

      @Override
      public String getDescription() {
        return "sleep";
      }

      @Override
      public void perform(UiController uiController, View view) {
        uiController.loopMainThreadForAtLeast(time);
      }
    };
  }

  private ViewAction activateSoftKeyboard() {
    return new ViewAction() {
      @Override
      public Matcher<View> getConstraints() {
        return isDisplayed();
      }

      @Override
      public String getDescription() {
        return "activateSoftKeyboard";
      }

      @Override
      public void perform(UiController uiController, View view) {
        InputEnabledTextView inputView = (InputEnabledTextView) view;
        InputConnection ic = inputView.getInputConnection();
        ic.setSoftKeyboardActive(true, 0);
        uiController.loopMainThreadForAtLeast(1000);
      }
    };
  }

  private ViewAction deactivateSoftKeyboard() {
    return new ViewAction() {
      @Override
      public Matcher<View> getConstraints() {
        return isDisplayed();
      }

      @Override
      public String getDescription() {
        return "deactivateSoftKeyboard";
      }

      @Override
      public void perform(UiController uiController, View view) {
        InputEnabledTextView inputView = (InputEnabledTextView) view;
        InputConnection ic = inputView.getInputConnection();
        ic.setSoftKeyboardActive(false, 0);
      }
    };
  }

  private ViewAction waitForIdle() {
    return new ViewAction() {
      @Override
      public Matcher<View> getConstraints() {
        return isDisplayed();
      }

      @Override
      public String getDescription() {
        return "waitForIdle";
      }

      @Override
      public void perform(UiController uiController, View view) {
        uiController.loopMainThreadUntilIdle();
      }
    };
  }

  private ViewAction setState(String text, int selectionStart, int selectionEnd) {
    return new ViewAction() {
      @Override
      public Matcher<View> getConstraints() {
        return isDisplayed();
      }

      @Override
      public String getDescription() {
        return "setState to: " + text + ", s: " + selectionStart + "-" + selectionEnd;
      }

      @Override
      public void perform(UiController uiController, View view) {
        InputEnabledTextView inputView = (InputEnabledTextView) view;
        InputConnection ic = inputView.getInputConnection();
        State state = new State(text, selectionStart, selectionEnd, -1, -1);
        ic.setState(state);
        uiController.loopMainThreadForAtLeast(500);
      }
    };
  }

  private ViewAction passKeyToOnKeyListener(int keyCode) {
    return new ViewAction() {
      @Override
      public Matcher<View> getConstraints() {
        return isDisplayed();
      }

      @Override
      public String getDescription() {
        return "Select text";
      }

      @Override
      public void perform(UiController uiController, View view) {
        InputEnabledTextView inputView = (InputEnabledTextView) view;
        InputConnection ic = inputView.getInputConnection();
        long eventTime = SystemClock.uptimeMillis();
        KeyEvent downEvent =
            new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0, 0);
        ic.onKey(view, keyCode, downEvent);
        KeyEvent upEvent = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0, 0);
        ic.onKey(view, keyCode, upEvent);
      }
    };
  }

  public void runGroupTests(TestGroup testGroup, Class<?> testClass) {
    List<Method> testsToRun = new ArrayList<>();
    for (Method method : testClass.getDeclaredMethods()) {
      if (method.isAnnotationPresent(TestToCombine.class)) {
        TestToCombine annotation = method.getAnnotation(TestToCombine.class);
        if (annotation.group().equals(testGroup)) {
          testsToRun.add(method);
        }
      }
    }

    for (Method test : testsToRun) {
      Log.d(TAG, "Executing test: " + test.getName());
      try {
        resetBeforeTest();
        test.invoke(this);
      } catch (InvocationTargetException | IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
