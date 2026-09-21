/*
 * Copyright 2022 The Android Open Source Project
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
 */

#include "test_utils.h"

#include <regex>
#include <sstream>
#include <list>
#include <mutex>
#include <random>

#include "gtest/gtest.h"

namespace gamesdk_test {

static std::list<std::unique_ptr<char[]>> allocated_bytes_list;
static std::mutex allocated_mutex;

// Wind forward over any arrays ... [...] ...
template<typename Iterator>
bool WindForwardOverArrays(Iterator& i, const Iterator& end,
                           char begin_ch = '[', char end_ch = ']',
                           std::string* inner = nullptr) {
  std::stringstream result;
  if (*i != begin_ch) return false;
  ++i;
  int level = 1;
  for (; i != end; ++i) {
    if (*i == begin_ch) ++level;
    if (*i == end_ch) {
      --level;
      if (level == 0) {
        ++i;
        if (inner != nullptr) *inner = result.str();
        return true;
      }
      if (level < 0) return false;
    }
    if (inner != nullptr) result << *i;
  }
  return false;
}

template<typename Iterator>
bool WindForwardOverRegex(const std::string& regex_str, Iterator& i,
                          const Iterator& end, std::string* inner = nullptr) {
  std::regex regex(regex_str);
  std::match_results<Iterator> result;
  // Match at the beginning of the string only.
  auto match_flags = std::regex_constants::match_continuous;
  if (std::regex_search(i, end, result, regex, match_flags)) {
    if (inner != nullptr) {
      *inner = result[0];
    }
    i += result[0].length();
    return true;
  }
  return false;
}

bool CompareIgnoringWhitespace(std::string s0, std::string s1,
                               std::string* error_msg) {
  // Ignore all whitespace, except when in strings.
  // '[**]' is a wildcard for any array.
  // '!REGEX(<regex>) is a regex.
  bool in_string = false;
  bool in_wildcard = false;
  auto a = s0.begin();
  auto b = s1.begin();
  auto produceErrorMessage = [&]() {
    if (error_msg != nullptr) {
      std::stringstream str;
      str << "Error at position " << (a - s0.begin()) << std::endl;
      str << std::string(std::max(s0.begin(), a - 20), a);
      str << "<HERE>";
      str << std::string(a, std::min(s0.end(), a + 20));
      str << std::endl;
      str << std::string(std::max(s1.begin(), b - 20), b);
      str << "<HERE>";
      str << std::string(b, std::min(s1.end(), b + 20));
      *error_msg = str.str();
    }
  };
  while (true) {
    if (!in_string) {
      while (a != s0.end() && isspace(*a)) a++;
      while (b != s1.end() && isspace(*b)) b++;
    }
    if (a == s0.end()) break;
    if (b == s1.end()) {
      produceErrorMessage();
      return false;
    }
    // Array wildcard
    if ((s1.end() - b) >= kArrayWildcard.length() &&
        std::string(b, b + kArrayWildcard.length()) == kArrayWildcard) {
      if (!WindForwardOverArrays(a, s0.end())) break;
      b += kArrayWildcard.length();
      continue;
    }
    // Regex
    if ((s1.end() - b) >= kRegexPattern.length() &&
        std::string(b, b + kRegexPattern.length()) == kRegexPattern) {
      b += kRegexPattern.length();
      std::string regex;
      if (!WindForwardOverArrays(b, s1.end(), '(', ')', &regex)) break;
      if (!WindForwardOverRegex(regex, a, s0.end())) break;
      continue;
    }
    if (*a != *b) {
      produceErrorMessage();
      return false;
    }
    if (*a == '"') in_string = !in_string;
    ++a;
    ++b;
  }
  if (b == s1.end()) {
    return true;
  } else {
    produceErrorMessage();
    return false;
  }
}

bool CheckStrings(const std::string& name, const std::string& result,
                  const std::string& expected) {
  std::string error_message;
  bool comp = CompareIgnoringWhitespace(result, expected, &error_message);
  EXPECT_TRUE(comp) << "\nResult:\n"
                    << result << "\n!=\nExpected:" << expected << "\n\n"
                    << error_message;
  return comp;
}


} // namespace gamesdk_test
