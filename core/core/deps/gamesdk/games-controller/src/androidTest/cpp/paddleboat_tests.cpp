/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// This file contains unit tests for the GameControllerMappingUtils class

#include <gtest/gtest.h>
#include <string.h>

#include "../../main/cpp/GameControllerMappingFile.h"
#include "../../main/cpp/GameControllerMappingUtils.h"
#include "paddleboat.h"

using namespace paddleboat;
using namespace std;

#define ARRAY_COUNTOF(array) (sizeof(array) / sizeof(array[0]))
#define PADDLEBOAT_AXIS_BUTTON_DPAD_UP 0
#define PADDLEBOAT_AXIS_BUTTON_DPAD_LEFT 1
#define PADDLEBOAT_AXIS_BUTTON_DPAD_DOWN 2
#define PADDLEBOAT_AXIS_BUTTON_DPAD_RIGHT 3
#define PADDLEBOAT_AXIS_BUTTON_L2 9
#define PADDLEBOAT_AXIS_BUTTON_R2 12

const IndexTableRemap pb_test_fake_remap[] = {{0}};

static Paddleboat_Controller_Mapping_File_Controller_Entry
    testMappingTable[GameControllerMappingInfo::MAX_CONTROLLER_TABLE_SIZE];

static Paddleboat_Controller_Mapping_File_String_Entry
    testStringTable[GameControllerMappingInfo::MAX_STRING_TABLE_SIZE];

static Paddleboat_Controller_Mapping_File_Axis_Entry
    testAxisTable[GameControllerMappingInfo::MAX_AXIS_TABLE_SIZE];

static Paddleboat_Controller_Mapping_File_Button_Entry
    testButtonTable[GameControllerMappingInfo::MAX_BUTTON_TABLE_SIZE];

static void InitSearchFromExistingEntry(
    const Paddleboat_Controller_Mapping_File_Controller_Entry *sourceEntry,
    MappingTableSearch &mapSearch) {
    mapSearch.initSearchParameters(sourceEntry->vendorId,
                                   sourceEntry->productId,
                                   sourceEntry->minimumEffectiveApiLevel,
                                   sourceEntry->maximumEffectiveApiLevel);
}

static bool ControllerEqual(
    const Paddleboat_Controller_Mapping_File_Controller_Entry &a,
    const Paddleboat_Controller_Mapping_File_Controller_Entry &b) {
    EXPECT_EQ(a.minimumEffectiveApiLevel, b.minimumEffectiveApiLevel);
    EXPECT_EQ(a.maximumEffectiveApiLevel, b.maximumEffectiveApiLevel);
    EXPECT_EQ(a.vendorId, b.vendorId);
    EXPECT_EQ(a.productId, b.productId);
    EXPECT_EQ(a.flags, b.flags);
    EXPECT_EQ(a.axisTableIndex, b.axisTableIndex);
    EXPECT_EQ(a.buttonTableIndex, b.buttonTableIndex);
    EXPECT_EQ(a.deviceAllowlistStringTableIndex,
              b.deviceAllowlistStringTableIndex);
    EXPECT_EQ(a.deviceDenylistStringTableIndex,
              b.deviceDenylistStringTableIndex);

    return (a.minimumEffectiveApiLevel == b.minimumEffectiveApiLevel &&
            a.maximumEffectiveApiLevel == b.maximumEffectiveApiLevel &&
            a.vendorId == b.vendorId && a.productId == b.productId &&
            a.flags == b.flags && a.axisTableIndex == b.axisTableIndex &&
            a.buttonTableIndex == b.buttonTableIndex &&
            a.deviceAllowlistStringTableIndex ==
                b.deviceAllowlistStringTableIndex &&
            a.deviceDenylistStringTableIndex ==
                b.deviceDenylistStringTableIndex);
}
// Make sure NotEmpty works
TEST(PaddleboatTestNE, NotEmpty) {
    EXPECT_NE(sizeof(Paddleboat_Controller_Data), 0);
}

// Make sure Validity works
// Also checks assumptions on data structure sizes
TEST(PaddleboatTestValidity, Validity) {
    const size_t pcd_size = sizeof(Paddleboat_Controller_Data);
    EXPECT_EQ(pcd_size, 64);
    EXPECT_NE(pcd_size, 0);
}

// Validate table tests
// --=========================================================================
// Test data
// Validation failure test, improper ordering of vendorId
const Paddleboat_Controller_Mapping_File_Controller_Entry
    pbtest_invalidmap_vendorid[] = {
        {16, 00, 0x0010, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0,
         0},
        {16, 00, 0x0020, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0,
         0},
        {16, 00, 0x0018, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0,
         0},
        {16, 00, 0x0030, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0,
         0},
};

// Validation failure test, improper ordering of productId
const Paddleboat_Controller_Mapping_File_Controller_Entry
    pbtest_invalidmap_productid[] = {
        {16, 00, 0x0010, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0,
         0},
        {16, 00, 0x0018, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0,
         0},
        {16, 00, 0x0020, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0,
         0},
        {16, 00, 0x0020, 0x0001, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0,
         0},
        {16, 00, 0x0030, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0,
         0}};

// Validation failure test, improper ordering of api levels
const Paddleboat_Controller_Mapping_File_Controller_Entry
    pbtest_invalidmap_apilevel[] = {
        {16, 00, 0x0010, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0,
         0},
        {16, 00, 0x0018, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0,
         0},
        {19, 00, 0x0020, 0x0001, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0,
         0},
        {16, 00, 0x0020, 0x0001, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0,
         0},
        {16, 00, 0x0030, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0,
         0}};

// Validation success test / find-insert tests baseline existing map
const Paddleboat_Controller_Mapping_File_Controller_Entry pbtest_validmap[] = {
    {16, 00, 0x0010, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0, 0},
    {16, 00, 0x0018, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0, 0},
    {16, 18, 0x0020, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0, 0},
    {19, 21, 0x0020, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0, 0},
    {16, 00, 0x0020, 0x0004, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0, 0},
    {16, 00, 0x0030, 0x0001, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0,
     0}};

// Test validateMapTable catches improper vendorId ordering
TEST(PaddleboatValidateMapVendorId, Validity) {
    const int32_t entryCount =
        static_cast<const int32_t>(ARRAY_COUNTOF(pbtest_invalidmap_vendorid));
    const Paddleboat_Controller_Mapping_File_Controller_Entry *errorEntry =
        GameControllerMappingUtils::validateMapTable(pbtest_invalidmap_vendorid,
                                                     entryCount);
    EXPECT_NE(errorEntry, nullptr);
    EXPECT_EQ(errorEntry->vendorId, 0x0018);
}

// Test validateMapTable catches improper productId ordering
TEST(PaddleboatValidateMapProductId, Validity) {
    const int32_t entryCount =
        static_cast<const int32_t>(ARRAY_COUNTOF(pbtest_invalidmap_productid));
    const Paddleboat_Controller_Mapping_File_Controller_Entry *errorEntry =
        GameControllerMappingUtils::validateMapTable(
            pbtest_invalidmap_productid, entryCount);
    EXPECT_NE(errorEntry, nullptr);
    EXPECT_EQ(errorEntry->vendorId, 0x0020);
    EXPECT_EQ(errorEntry->productId, 0x0001);
}

// Test validateMapTable catches improper api level ordering
TEST(PaddleboatValidateMapApiLevel, Validity) {
    const int32_t entryCount =
        static_cast<const int32_t>(ARRAY_COUNTOF(pbtest_invalidmap_apilevel));
    const Paddleboat_Controller_Mapping_File_Controller_Entry *errorEntry =
        GameControllerMappingUtils::validateMapTable(pbtest_invalidmap_apilevel,
                                                     entryCount);
    EXPECT_NE(errorEntry, nullptr);
    EXPECT_EQ(errorEntry->vendorId, 0x0020);
    EXPECT_EQ(errorEntry->productId, 0x0001);
    EXPECT_EQ(errorEntry->minimumEffectiveApiLevel, 16);
}

// Test validateMapTable returns nullptr on a successful validation
TEST(PaddleboatValidateMapSuccess, Validity) {
    const int32_t entryCount =
        static_cast<const int32_t>(ARRAY_COUNTOF(pbtest_validmap));
    const Paddleboat_Controller_Mapping_File_Controller_Entry *errorEntry =
        GameControllerMappingUtils::validateMapTable(pbtest_validmap,
                                                     entryCount);
    EXPECT_EQ(errorEntry, nullptr);
}

// Controller find test
// ===========================================================================
// Test data

TEST(PaddleboatValidateFindMapEntry, Validity) {
    const int32_t entryCount =
        static_cast<const int32_t>(ARRAY_COUNTOF(pbtest_validmap));
    memcpy(testMappingTable, pbtest_validmap,
           entryCount *
               sizeof(Paddleboat_Controller_Mapping_File_Controller_Entry));
    MappingTableSearch mapSearch(&testMappingTable[0], entryCount);

    // Should find a match for the first entry
    int32_t targetTableIndex = 0;
    InitSearchFromExistingEntry(&testMappingTable[targetTableIndex], mapSearch);
    bool foundEntry =
        GameControllerMappingUtils::findMatchingMapEntry(&mapSearch);
    EXPECT_EQ(foundEntry, true);
    EXPECT_EQ(mapSearch.tableIndex, targetTableIndex);

    // Should find a match for the fourth entry
    targetTableIndex = 3;
    InitSearchFromExistingEntry(&testMappingTable[targetTableIndex], mapSearch);
    foundEntry = GameControllerMappingUtils::findMatchingMapEntry(&mapSearch);
    EXPECT_EQ(foundEntry, true);
    EXPECT_EQ(mapSearch.tableIndex, targetTableIndex);

    // Should fail to find a match and put insert point at the first entry
    // (unique productId/vendorId)
    mapSearch.initSearchParameters(0x1, 0x1, 16, 0);
    foundEntry = GameControllerMappingUtils::findMatchingMapEntry(&mapSearch);
    EXPECT_EQ(foundEntry, false);
    EXPECT_EQ(mapSearch.tableIndex, 0);

    // Should fail to find a match and put insert point at the third entry
    // (matching productId/new vendorId)
    mapSearch.initSearchParameters(0x20, 0x1, 16, 0);
    foundEntry = GameControllerMappingUtils::findMatchingMapEntry(&mapSearch);
    EXPECT_EQ(foundEntry, false);
    EXPECT_EQ(mapSearch.tableIndex, 2);

    // Should fail to find a match and put insert point at the fifth entry
    // (matching productId/vendorId, new apiLevel)
    mapSearch.initSearchParameters(0x20, 0x2, 22, 0);
    foundEntry = GameControllerMappingUtils::findMatchingMapEntry(&mapSearch);
    EXPECT_EQ(foundEntry, false);
    EXPECT_EQ(mapSearch.tableIndex, 4);

    // Should fail to find a match and put insert point at the sixth entry
    // (unique productId/vendorId)
    mapSearch.initSearchParameters(0x21, 0x1, 16, 0);
    foundEntry = GameControllerMappingUtils::findMatchingMapEntry(&mapSearch);
    EXPECT_EQ(foundEntry, false);
    EXPECT_EQ(mapSearch.tableIndex, 5);

    // Should fail to find a match and put the insert point at the end of the
    // table (unique productId/vendorId)
    mapSearch.initSearchParameters(0x40, 0x1, 16, 0);
    foundEntry = GameControllerMappingUtils::findMatchingMapEntry(&mapSearch);
    EXPECT_EQ(foundEntry, false);
    EXPECT_EQ(mapSearch.tableIndex, entryCount);
}

// Controller insert test
// =========================================================================
// Test data
const Paddleboat_Controller_Mapping_File_Controller_Entry pbtest_add_entry1 = {
    16, 00, 0x0001, 0x0001, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0, 0};

const Paddleboat_Controller_Mapping_File_Controller_Entry pbtest_add_entry2 = {
    16, 00, 0x0020, 0x0010, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0, 0};

const Paddleboat_Controller_Mapping_File_Controller_Entry pbtest_add_entry3 = {
    16, 00, 0x0040, 0x0100, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0, 0};

TEST(PaddleboatValidateInsertMapEntry, Validity) {
    Paddleboat_ErrorCode errorCode = PADDLEBOAT_NO_ERROR;
    const int32_t entryCount =
        static_cast<const int32_t>(ARRAY_COUNTOF(pbtest_validmap));
    memset(testMappingTable, 0,
           GameControllerMappingInfo::MAX_CONTROLLER_TABLE_SIZE *
               sizeof(Paddleboat_Controller_Mapping_File_Controller_Entry));
    memcpy(testMappingTable, pbtest_validmap,
           entryCount *
               sizeof(Paddleboat_Controller_Mapping_File_Controller_Entry));
    MappingTableSearch mapSearch(&testMappingTable[0], entryCount);

    // Insert should happen at beginning of table
    InitSearchFromExistingEntry(&pbtest_add_entry1, mapSearch);
    GameControllerMappingUtils::findMatchingMapEntry(&mapSearch);
    errorCode = GameControllerMappingUtils::insertMapEntry(
        &pbtest_add_entry1, &mapSearch, pb_test_fake_remap, pb_test_fake_remap);
    EXPECT_EQ(errorCode, PADDLEBOAT_NO_ERROR);
    EXPECT_EQ(testMappingTable[0].vendorId, pbtest_add_entry1.vendorId);
    EXPECT_EQ(testMappingTable[0].productId, pbtest_add_entry1.productId);
    EXPECT_EQ(testMappingTable[6].vendorId, 0x30);
    EXPECT_EQ(testMappingTable[6].productId, 0x1);

    // Insert should happen in next to last entry
    // reset mapping table
    memset(testMappingTable, 0,
           GameControllerMappingInfo::MAX_CONTROLLER_TABLE_SIZE *
               sizeof(Paddleboat_Controller_Mapping_File_Controller_Entry));
    memcpy(testMappingTable, pbtest_validmap,
           entryCount *
               sizeof(Paddleboat_Controller_Mapping_File_Controller_Entry));
    mapSearch.tableEntryCount = entryCount;

    InitSearchFromExistingEntry(&pbtest_add_entry2, mapSearch);
    GameControllerMappingUtils::findMatchingMapEntry(&mapSearch);
    errorCode = GameControllerMappingUtils::insertMapEntry(
        &pbtest_add_entry2, &mapSearch, pb_test_fake_remap, pb_test_fake_remap);
    EXPECT_EQ(errorCode, PADDLEBOAT_NO_ERROR);
    EXPECT_EQ(testMappingTable[5].vendorId, pbtest_add_entry2.vendorId);
    EXPECT_EQ(testMappingTable[5].productId, pbtest_add_entry2.productId);
    EXPECT_EQ(testMappingTable[6].vendorId, 0x30);
    EXPECT_EQ(testMappingTable[6].productId, 0x1);

    // Insert should be at end of table
    // reset mapping table
    memset(testMappingTable, 0,
           GameControllerMappingInfo::MAX_CONTROLLER_TABLE_SIZE *
               sizeof(Paddleboat_Controller_Mapping_File_Controller_Entry));
    memcpy(testMappingTable, pbtest_validmap,
           entryCount *
               sizeof(Paddleboat_Controller_Mapping_File_Controller_Entry));
    mapSearch.tableEntryCount = entryCount;

    InitSearchFromExistingEntry(&pbtest_add_entry3, mapSearch);
    GameControllerMappingUtils::findMatchingMapEntry(&mapSearch);
    errorCode = GameControllerMappingUtils::insertMapEntry(
        &pbtest_add_entry3, &mapSearch, pb_test_fake_remap, pb_test_fake_remap);
    EXPECT_EQ(errorCode, PADDLEBOAT_NO_ERROR);
    EXPECT_EQ(testMappingTable[5].vendorId, 0x30);
    EXPECT_EQ(testMappingTable[5].productId, 0x1);
    EXPECT_EQ(testMappingTable[6].vendorId, pbtest_add_entry3.vendorId);
    EXPECT_EQ(testMappingTable[6].productId, pbtest_add_entry3.productId);

    // Test failure on full table
    // reset mapping table
    memset(testMappingTable, 0,
           GameControllerMappingInfo::MAX_CONTROLLER_TABLE_SIZE *
               sizeof(Paddleboat_Controller_Mapping_File_Controller_Entry));
    memcpy(testMappingTable, pbtest_validmap,
           entryCount *
               sizeof(Paddleboat_Controller_Mapping_File_Controller_Entry));
    mapSearch.tableEntryCount = entryCount;

    mapSearch.tableMaxEntryCount = entryCount;
    InitSearchFromExistingEntry(&pbtest_add_entry3, mapSearch);
    GameControllerMappingUtils::findMatchingMapEntry(&mapSearch);
    errorCode = GameControllerMappingUtils::insertMapEntry(
        &pbtest_add_entry3, &mapSearch, pb_test_fake_remap, pb_test_fake_remap);
    EXPECT_EQ(errorCode, PADDLEBOAT_ERROR_FEATURE_NOT_SUPPORTED);
}

// String remap test
// ==============================================================================
// Test data
const Paddleboat_Controller_Mapping_File_String_Entry pbtest_stringstart[] = {
    {"None"},               // 00
    {"Generic_Axis"},       // 01
    {"XB_Button"},          // 02
    {"DS_Button"},          // 03
    {"DS5_Compat_Button"},  // 04
};

const Paddleboat_Controller_Mapping_File_String_Entry pbtest_stringadd1[] = {
    {"NinSPro_Button"},  // 05
    {"Generic_Button"},  // 06
};

const Paddleboat_Controller_Mapping_File_String_Entry pbtest_stringadd2[] = {
    {"Generic_Axis"},      // 01
    {"XB_Button"},         // 02
    {"NGPro_PC2_Button"},  // 07
    {"DS5_Compat_Axis"}    // 08
};

const Paddleboat_Controller_Mapping_File_String_Entry pbtest_stringadd3[] = {
    {"Placeholder_space"}};

TEST(PaddleboatValidateStringTableMerge, Validity) {
    Paddleboat_ErrorCode errorCode = PADDLEBOAT_NO_ERROR;
    memset(testStringTable, 0,
           GameControllerMappingInfo::MAX_STRING_TABLE_SIZE *
               sizeof(Paddleboat_Controller_Mapping_File_String_Entry));
    const uint32_t entryCount =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pbtest_stringstart));
    EXPECT_EQ(entryCount, 5);
    memcpy(
        testStringTable, pbtest_stringstart,
        entryCount * sizeof(Paddleboat_Controller_Mapping_File_String_Entry));
    IndexTableRemap
        stringTableRemap[GameControllerMappingInfo::MAX_STRING_TABLE_SIZE];

    // Expect two new strings appended to end of existing table, with two remap
    // table entries
    memset(stringTableRemap, 0,
           GameControllerMappingInfo::MAX_STRING_TABLE_SIZE *
               sizeof(IndexTableRemap));
    uint32_t currentEntryCount = entryCount;
    const uint32_t add1Count =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pbtest_stringadd1));
    errorCode = GameControllerMappingUtils::mergeStringTable(
        pbtest_stringadd1, add1Count, testStringTable, &currentEntryCount,
        GameControllerMappingInfo::MAX_STRING_TABLE_SIZE, stringTableRemap);
    EXPECT_EQ(errorCode, PADDLEBOAT_NO_ERROR);
    EXPECT_EQ(currentEntryCount, 7);
    EXPECT_EQ(stringTableRemap[0].newIndex,
              5);  // index 0 old table, 5 new table
    EXPECT_EQ(stringTableRemap[1].newIndex,
              6);  // index 1 old table, 6 new table
    EXPECT_EQ(strcmp(testStringTable[5].stringTableEntry,
                     pbtest_stringadd1[0].stringTableEntry),
              0);
    EXPECT_EQ(strcmp(testStringTable[6].stringTableEntry,
                     pbtest_stringadd1[1].stringTableEntry),
              0);

    // Expect two new strings appended to end of existing table, with two remap
    // table entries, and two skipped duplicates
    memset(stringTableRemap, 0,
           GameControllerMappingInfo::MAX_STRING_TABLE_SIZE *
               sizeof(IndexTableRemap));
    const uint32_t add2Count =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pbtest_stringadd2));
    errorCode = GameControllerMappingUtils::mergeStringTable(
        pbtest_stringadd2, add2Count, testStringTable, &currentEntryCount,
        GameControllerMappingInfo::MAX_STRING_TABLE_SIZE, stringTableRemap);
    EXPECT_EQ(errorCode, PADDLEBOAT_NO_ERROR);
    EXPECT_EQ(currentEntryCount, 9);
    EXPECT_EQ(stringTableRemap[0].newIndex,
              1);  // index 0 old table, 1 new table
    EXPECT_EQ(stringTableRemap[1].newIndex,
              2);  // index 1 old table, 2 new table
    EXPECT_EQ(stringTableRemap[2].newIndex,
              7);  // index 2 old table, 7 new table
    EXPECT_EQ(stringTableRemap[3].newIndex,
              8);  // index 3 old table, 8 new table
    EXPECT_EQ(strcmp(testStringTable[7].stringTableEntry,
                     pbtest_stringadd2[2].stringTableEntry),
              0);
    EXPECT_EQ(strcmp(testStringTable[8].stringTableEntry,
                     pbtest_stringadd2[3].stringTableEntry),
              0);

    // Expect out of space error condition
    currentEntryCount = GameControllerMappingInfo::MAX_STRING_TABLE_SIZE;
    memset(stringTableRemap, 0,
           GameControllerMappingInfo::MAX_STRING_TABLE_SIZE *
               sizeof(IndexTableRemap));
    const uint32_t add3Count =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pbtest_stringadd3));
    errorCode = GameControllerMappingUtils::mergeStringTable(
        pbtest_stringadd3, add3Count, testStringTable, &currentEntryCount,
        GameControllerMappingInfo::MAX_STRING_TABLE_SIZE, stringTableRemap);
    EXPECT_EQ(errorCode, PADDLEBOAT_ERROR_FEATURE_NOT_SUPPORTED);
}

// Axis remap test
// ================================================================================
// Test data

const Paddleboat_Controller_Mapping_File_Axis_Entry pb_test_axis_start[] = {
    {1,  // "Generic_Axis"
     {
         /* LX */ AMOTION_EVENT_AXIS_X,
         /* LY */ AMOTION_EVENT_AXIS_Y,
         /* RX */ AMOTION_EVENT_AXIS_Z,
         /* RY */ AMOTION_EVENT_AXIS_RZ,
         /* L1 */ PADDLEBOAT_AXIS_IGNORED,
         /* L2 */ AMOTION_EVENT_AXIS_BRAKE,
         /* R1 */ PADDLEBOAT_AXIS_IGNORED,
         /* R2 */ AMOTION_EVENT_AXIS_GAS,
         /* HX */ AMOTION_EVENT_AXIS_HAT_X,
         /* HY */ AMOTION_EVENT_AXIS_HAT_Y,
     },
     {
         /* LX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* LY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L2 */ PADDLEBOAT_AXIS_BUTTON_L2,
         /* R1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R2 */ PADDLEBOAT_AXIS_BUTTON_R2,
         /* HX */ PADDLEBOAT_AXIS_BUTTON_DPAD_RIGHT,
         /* HY */ PADDLEBOAT_AXIS_BUTTON_DPAD_DOWN,
     },
     {
         /* LX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* LY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L2 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R2 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* HX */ PADDLEBOAT_AXIS_BUTTON_DPAD_LEFT,
         /* HY */ PADDLEBOAT_AXIS_BUTTON_DPAD_UP,
     },
     0}};

const Paddleboat_Controller_Mapping_File_Axis_Entry pb_test_axis_add1[] = {
    {0,  // "Foo_Axis"
     {
         /* LX */ AMOTION_EVENT_AXIS_Y,
         /* LY */ AMOTION_EVENT_AXIS_X,
         /* RX */ AMOTION_EVENT_AXIS_Z,
         /* RY */ AMOTION_EVENT_AXIS_RZ,
         /* L1 */ PADDLEBOAT_AXIS_IGNORED,
         /* L2 */ AMOTION_EVENT_AXIS_BRAKE,
         /* R1 */ PADDLEBOAT_AXIS_IGNORED,
         /* R2 */ AMOTION_EVENT_AXIS_GAS,
         /* HX */ AMOTION_EVENT_AXIS_HAT_X,
         /* HY */ AMOTION_EVENT_AXIS_HAT_Y,
     },
     {
         /* LX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* LY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L2 */ PADDLEBOAT_AXIS_BUTTON_L2,
         /* R1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R2 */ PADDLEBOAT_AXIS_BUTTON_R2,
         /* HX */ PADDLEBOAT_AXIS_BUTTON_DPAD_RIGHT,
         /* HY */ PADDLEBOAT_AXIS_BUTTON_DPAD_DOWN,
     },
     {
         /* LX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* LY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L2 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R2 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* HX */ PADDLEBOAT_AXIS_BUTTON_DPAD_LEFT,
         /* HY */ PADDLEBOAT_AXIS_BUTTON_DPAD_UP,
     },
     0}};

const Paddleboat_Controller_Mapping_File_Axis_Entry pb_test_axis_add2[] = {
    {0,  // "Generic_Axis"
     {
         /* LX */ AMOTION_EVENT_AXIS_X,
         /* LY */ AMOTION_EVENT_AXIS_Y,
         /* RX */ AMOTION_EVENT_AXIS_Z,
         /* RY */ AMOTION_EVENT_AXIS_RZ,
         /* L1 */ PADDLEBOAT_AXIS_IGNORED,
         /* L2 */ AMOTION_EVENT_AXIS_BRAKE,
         /* R1 */ PADDLEBOAT_AXIS_IGNORED,
         /* R2 */ AMOTION_EVENT_AXIS_GAS,
         /* HX */ AMOTION_EVENT_AXIS_HAT_X,
         /* HY */ AMOTION_EVENT_AXIS_HAT_Y,
     },
     {
         /* LX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* LY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L2 */ PADDLEBOAT_AXIS_BUTTON_L2,
         /* R1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R2 */ PADDLEBOAT_AXIS_BUTTON_R2,
         /* HX */ PADDLEBOAT_AXIS_BUTTON_DPAD_RIGHT,
         /* HY */ PADDLEBOAT_AXIS_BUTTON_DPAD_DOWN,
     },
     {
         /* LX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* LY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L2 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R2 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* HX */ PADDLEBOAT_AXIS_BUTTON_DPAD_LEFT,
         /* HY */ PADDLEBOAT_AXIS_BUTTON_DPAD_UP,
     },
     0},
    {1,  // "Bar_Axis"
     {
         /* LX */ AMOTION_EVENT_AXIS_Z,
         /* LY */ AMOTION_EVENT_AXIS_Y,
         /* RX */ AMOTION_EVENT_AXIS_X,
         /* RY */ AMOTION_EVENT_AXIS_RZ,
         /* L1 */ PADDLEBOAT_AXIS_IGNORED,
         /* L2 */ AMOTION_EVENT_AXIS_BRAKE,
         /* R1 */ PADDLEBOAT_AXIS_IGNORED,
         /* R2 */ AMOTION_EVENT_AXIS_GAS,
         /* HX */ AMOTION_EVENT_AXIS_HAT_X,
         /* HY */ AMOTION_EVENT_AXIS_HAT_Y,
     },
     {
         /* LX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* LY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L2 */ PADDLEBOAT_AXIS_BUTTON_L2,
         /* R1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R2 */ PADDLEBOAT_AXIS_BUTTON_R2,
         /* HX */ PADDLEBOAT_AXIS_BUTTON_DPAD_RIGHT,
         /* HY */ PADDLEBOAT_AXIS_BUTTON_DPAD_DOWN,
     },
     {
         /* LX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* LY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L2 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R2 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* HX */ PADDLEBOAT_AXIS_BUTTON_DPAD_LEFT,
         /* HY */ PADDLEBOAT_AXIS_BUTTON_DPAD_UP,
     },
     0}};

const Paddleboat_Controller_Mapping_File_String_Entry pbtest_axisstringstart[] =
    {
        {"None"},          // 00
        {"Generic_Axis"},  // 01
};

const Paddleboat_Controller_Mapping_File_String_Entry pb_test_axisstr_add1[] = {
    {"Foo_Axis"},  // 00
};

const Paddleboat_Controller_Mapping_File_String_Entry pb_test_axisstr_add2[] = {
    {"Generic_Axis"},  // 00
    {"Bar_Axis"},      // 01
};

const Paddleboat_Controller_Mapping_File_String_Entry pb_test_axisstr_add3[] = {
    {"Why_Axis"},  // 00
};

TEST(PaddleboatValidateAxisTableMerge, Validity) {
    Paddleboat_ErrorCode errorCode = PADDLEBOAT_NO_ERROR;
    // Init axis table
    memset(testAxisTable, 0,
           GameControllerMappingInfo::MAX_AXIS_TABLE_SIZE *
               sizeof(Paddleboat_Controller_Mapping_File_Axis_Entry));
    const uint32_t entryCount =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pb_test_axis_start));
    EXPECT_EQ(entryCount, 1);
    memcpy(testAxisTable, pb_test_axis_start,
           entryCount * sizeof(Paddleboat_Controller_Mapping_File_Axis_Entry));
    // Init string table
    memset(testStringTable, 0,
           GameControllerMappingInfo::MAX_STRING_TABLE_SIZE *
               sizeof(Paddleboat_Controller_Mapping_File_String_Entry));
    const uint32_t stringEntryCount =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pbtest_axisstringstart));
    EXPECT_EQ(stringEntryCount, 2);
    memcpy(testStringTable, pbtest_axisstringstart,
           stringEntryCount *
               sizeof(Paddleboat_Controller_Mapping_File_String_Entry));
    // Init remap tables
    IndexTableRemap
        stringTableRemap[GameControllerMappingInfo::MAX_STRING_TABLE_SIZE];
    IndexTableRemap
        axisTableRemap[GameControllerMappingInfo::MAX_AXIS_TABLE_SIZE];
    memset(stringTableRemap, 0,
           GameControllerMappingInfo::MAX_STRING_TABLE_SIZE *
               sizeof(IndexTableRemap));
    memset(axisTableRemap, 0,
           GameControllerMappingInfo::MAX_AXIS_TABLE_SIZE *
               sizeof(IndexTableRemap));

    // Test 1, have to merge string table first
    uint32_t currentStringEntryCount = stringEntryCount;
    const uint32_t strAdd1Count =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pb_test_axisstr_add1));
    errorCode = GameControllerMappingUtils::mergeStringTable(
        pb_test_axisstr_add1, strAdd1Count, testStringTable,
        &currentStringEntryCount,
        GameControllerMappingInfo::MAX_STRING_TABLE_SIZE, stringTableRemap);
    EXPECT_EQ(errorCode, PADDLEBOAT_NO_ERROR);
    EXPECT_EQ(stringTableRemap[0].newIndex,
              2);  // index 0 old table, 2 new table

    // Should append one axis entry and remap its axis and string indices
    uint32_t currentEntryCount = entryCount;
    const uint32_t add1Count =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pb_test_axis_add1));
    errorCode = GameControllerMappingUtils::mergeAxisTable(
        pb_test_axis_add1, add1Count, testAxisTable, &currentEntryCount,
        GameControllerMappingInfo::MAX_AXIS_TABLE_SIZE, stringTableRemap,
        axisTableRemap);
    EXPECT_EQ(errorCode, PADDLEBOAT_NO_ERROR);
    EXPECT_EQ(currentEntryCount, 2);
    EXPECT_EQ(axisTableRemap[0].newIndex, 1);  // index 0 old table, 1 new table
    EXPECT_EQ(testAxisTable[0].axisNameStringTableIndex, 1);
    EXPECT_EQ(testAxisTable[0].axisMapping[0], AMOTION_EVENT_AXIS_X);
    EXPECT_EQ(testAxisTable[1].axisNameStringTableIndex, 2);
    EXPECT_EQ(testAxisTable[1].axisMapping[0], AMOTION_EVENT_AXIS_Y);

    // Test 2, reset remap tables and merge string table first
    memset(stringTableRemap, 0,
           GameControllerMappingInfo::MAX_STRING_TABLE_SIZE *
               sizeof(IndexTableRemap));
    memset(axisTableRemap, 0,
           GameControllerMappingInfo::MAX_AXIS_TABLE_SIZE *
               sizeof(IndexTableRemap));
    const uint32_t strAdd2Count =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pb_test_axisstr_add2));
    errorCode = GameControllerMappingUtils::mergeStringTable(
        pb_test_axisstr_add2, strAdd2Count, testStringTable,
        &currentStringEntryCount,
        GameControllerMappingInfo::MAX_STRING_TABLE_SIZE, stringTableRemap);
    EXPECT_EQ(errorCode, PADDLEBOAT_NO_ERROR);
    EXPECT_EQ(stringTableRemap[0].newIndex,
              1);  // index 0 old table, 1 new table
    EXPECT_EQ(stringTableRemap[1].newIndex,
              3);  // index 1 old table, 3 new table

    // Should append one axis entry and remap its axis and string indices
    // and ignore a duplicate
    const uint32_t add2Count =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pb_test_axis_add2));
    errorCode = GameControllerMappingUtils::mergeAxisTable(
        pb_test_axis_add2, add2Count, testAxisTable, &currentEntryCount,
        GameControllerMappingInfo::MAX_AXIS_TABLE_SIZE, stringTableRemap,
        axisTableRemap);
    EXPECT_EQ(errorCode, PADDLEBOAT_NO_ERROR);
    EXPECT_EQ(currentEntryCount, 3);
    EXPECT_EQ(axisTableRemap[0].newIndex, 0);  // index 0 old table, 0 new table
    EXPECT_EQ(axisTableRemap[1].newIndex, 2);  // index 1 old table, 2 new table
    EXPECT_EQ(testAxisTable[0].axisNameStringTableIndex, 1);
    EXPECT_EQ(testAxisTable[0].axisMapping[0], AMOTION_EVENT_AXIS_X);
    EXPECT_EQ(testAxisTable[1].axisNameStringTableIndex, 2);
    EXPECT_EQ(testAxisTable[1].axisMapping[0], AMOTION_EVENT_AXIS_Y);
    EXPECT_EQ(testAxisTable[2].axisNameStringTableIndex, 3);
    EXPECT_EQ(testAxisTable[2].axisMapping[0], AMOTION_EVENT_AXIS_Z);

    // Test 3, reset remap tables and merge string table first
    memset(stringTableRemap, 0,
           GameControllerMappingInfo::MAX_STRING_TABLE_SIZE *
               sizeof(IndexTableRemap));
    memset(axisTableRemap, 0,
           GameControllerMappingInfo::MAX_AXIS_TABLE_SIZE *
               sizeof(IndexTableRemap));
    const uint32_t strAdd3Count =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pb_test_axisstr_add3));
    errorCode = GameControllerMappingUtils::mergeStringTable(
        pb_test_axisstr_add3, strAdd3Count, testStringTable,
        &currentStringEntryCount,
        GameControllerMappingInfo::MAX_STRING_TABLE_SIZE, stringTableRemap);
    EXPECT_EQ(errorCode, PADDLEBOAT_NO_ERROR);
    EXPECT_EQ(stringTableRemap[0].newIndex,
              4);  // index 0 old table, 4 new table

    // Should return no free space error, reuse add1 since the string table
    // remap counts as a 'new' axis
    currentEntryCount = GameControllerMappingInfo::MAX_AXIS_TABLE_SIZE;
    const uint32_t add3Count =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pb_test_axis_add1));
    errorCode = GameControllerMappingUtils::mergeAxisTable(
        pb_test_axis_add1, add3Count, testAxisTable, &currentEntryCount,
        GameControllerMappingInfo::MAX_AXIS_TABLE_SIZE, stringTableRemap,
        axisTableRemap);
    EXPECT_EQ(errorCode, PADDLEBOAT_ERROR_FEATURE_NOT_SUPPORTED);
}

// Button remap test
// ==============================================================================
// Test data
const Paddleboat_Controller_Mapping_File_Button_Entry pb_test_button_start[] = {
    {2,  // "XB_Button"
     {/* UP     */ AKEYCODE_DPAD_UP,
      /* LEFT   */ AKEYCODE_DPAD_LEFT,
      /* DOWN   */ AKEYCODE_DPAD_DOWN,
      /* RIGHT  */ AKEYCODE_DPAD_RIGHT,
      /* A      */ AKEYCODE_BUTTON_A,
      /* B      */ AKEYCODE_BUTTON_B,
      /* X      */ AKEYCODE_BUTTON_X,
      /* Y      */ AKEYCODE_BUTTON_Y,
      /* L1     */ AKEYCODE_BUTTON_L1,
      /* L2     */ AKEYCODE_BUTTON_L2,
      /* L3     */ AKEYCODE_BUTTON_THUMBL,
      /* R1     */ AKEYCODE_BUTTON_R1,
      /* R2     */ AKEYCODE_BUTTON_R2,
      /* R3     */ AKEYCODE_BUTTON_THUMBR,
      /* SELECT */ AKEYCODE_BUTTON_SELECT,
      /* START  */ AKEYCODE_BUTTON_START,
      /* SYSTEM */ AKEYCODE_BUTTON_MODE,
      /* TOUCHP */ PADDLEBOAT_BUTTON_IGNORED,
      /* AUX1   */ AKEYCODE_MEDIA_RECORD,
      /* AUX2   */ PADDLEBOAT_BUTTON_IGNORED,
      /* AUX3   */ PADDLEBOAT_BUTTON_IGNORED,
      /* AUX4   */ PADDLEBOAT_BUTTON_IGNORED}}};

const Paddleboat_Controller_Mapping_File_Button_Entry pb_test_button_add1[] = {
    {0,  // "DS_Button"
     {/* UP     */ AKEYCODE_DPAD_DOWN,
      /* LEFT   */ AKEYCODE_DPAD_LEFT,
      /* DOWN   */ AKEYCODE_DPAD_UP,
      /* RIGHT  */ AKEYCODE_DPAD_RIGHT,
      /* A      */ AKEYCODE_BUTTON_A,
      /* B      */ AKEYCODE_BUTTON_B,
      /* X      */ AKEYCODE_BUTTON_X,
      /* Y      */ AKEYCODE_BUTTON_Y,
      /* L1     */ AKEYCODE_BUTTON_L1,
      /* L2     */ AKEYCODE_BUTTON_L2,
      /* L3     */ AKEYCODE_BUTTON_THUMBL,
      /* R1     */ AKEYCODE_BUTTON_R1,
      /* R2     */ AKEYCODE_BUTTON_R2,
      /* R3     */ AKEYCODE_BUTTON_THUMBR,
      /* SELECT */ AKEYCODE_BUTTON_SELECT,
      /* START  */ AKEYCODE_BUTTON_START,
      /* SYSTEM */ AKEYCODE_BUTTON_MODE,
      /* TOUCHP */ PADDLEBOAT_BUTTON_IGNORED,
      /* AUX1   */ AKEYCODE_MEDIA_RECORD,
      /* AUX2   */ PADDLEBOAT_BUTTON_IGNORED,
      /* AUX3   */ PADDLEBOAT_BUTTON_IGNORED,
      /* AUX4   */ PADDLEBOAT_BUTTON_IGNORED}}};

const Paddleboat_Controller_Mapping_File_Button_Entry pb_test_button_add2[] = {
    {0,  // "XB_Button"
     {/* UP     */ AKEYCODE_DPAD_UP,
      /* LEFT   */ AKEYCODE_DPAD_LEFT,
      /* DOWN   */ AKEYCODE_DPAD_DOWN,
      /* RIGHT  */ AKEYCODE_DPAD_RIGHT,
      /* A      */ AKEYCODE_BUTTON_A,
      /* B      */ AKEYCODE_BUTTON_B,
      /* X      */ AKEYCODE_BUTTON_X,
      /* Y      */ AKEYCODE_BUTTON_Y,
      /* L1     */ AKEYCODE_BUTTON_L1,
      /* L2     */ AKEYCODE_BUTTON_L2,
      /* L3     */ AKEYCODE_BUTTON_THUMBL,
      /* R1     */ AKEYCODE_BUTTON_R1,
      /* R2     */ AKEYCODE_BUTTON_R2,
      /* R3     */ AKEYCODE_BUTTON_THUMBR,
      /* SELECT */ AKEYCODE_BUTTON_SELECT,
      /* START  */ AKEYCODE_BUTTON_START,
      /* SYSTEM */ AKEYCODE_BUTTON_MODE,
      /* TOUCHP */ PADDLEBOAT_BUTTON_IGNORED,
      /* AUX1   */ AKEYCODE_MEDIA_RECORD,
      /* AUX2   */ PADDLEBOAT_BUTTON_IGNORED,
      /* AUX3   */ PADDLEBOAT_BUTTON_IGNORED,
      /* AUX4   */ PADDLEBOAT_BUTTON_IGNORED}},
    {1,  // "SP_Button"
     {/* UP     */ AKEYCODE_DPAD_LEFT,
      /* LEFT   */ AKEYCODE_DPAD_DOWN,
      /* DOWN   */ AKEYCODE_DPAD_RIGHT,
      /* RIGHT  */ AKEYCODE_DPAD_UP,
      /* A      */ AKEYCODE_BUTTON_A,
      /* B      */ AKEYCODE_BUTTON_B,
      /* X      */ AKEYCODE_BUTTON_X,
      /* Y      */ AKEYCODE_BUTTON_Y,
      /* L1     */ AKEYCODE_BUTTON_L1,
      /* L2     */ AKEYCODE_BUTTON_L2,
      /* L3     */ AKEYCODE_BUTTON_THUMBL,
      /* R1     */ AKEYCODE_BUTTON_R1,
      /* R2     */ AKEYCODE_BUTTON_R2,
      /* R3     */ AKEYCODE_BUTTON_THUMBR,
      /* SELECT */ AKEYCODE_BUTTON_SELECT,
      /* START  */ AKEYCODE_BUTTON_START,
      /* SYSTEM */ AKEYCODE_BUTTON_MODE,
      /* TOUCHP */ PADDLEBOAT_BUTTON_IGNORED,
      /* AUX1   */ AKEYCODE_MEDIA_RECORD,
      /* AUX2   */ PADDLEBOAT_BUTTON_IGNORED,
      /* AUX3   */ PADDLEBOAT_BUTTON_IGNORED,
      /* AUX4   */ PADDLEBOAT_BUTTON_IGNORED}}};

const Paddleboat_Controller_Mapping_File_String_Entry
    pbtest_buttonstringstart[] = {
        {"None"},          // 00
        {"Generic_Axis"},  // 01
        {"XB_Button"}      // 02
};

const Paddleboat_Controller_Mapping_File_String_Entry pb_test_buttonstr_add1[] =
    {
        {"DS_Button"},  // 00
};

const Paddleboat_Controller_Mapping_File_String_Entry pb_test_buttonstr_add2[] =
    {
        {"XB_Button"},  // 00
        {"SP_Button"},  // 01
};

const Paddleboat_Controller_Mapping_File_String_Entry pb_test_buttonstr_add3[] =
    {
        {"Foo_Button"},  // 00
};

TEST(PaddleboatValidateButtonTableMerge, Validity) {
    Paddleboat_ErrorCode errorCode = PADDLEBOAT_NO_ERROR;
    // Init button table
    memset(testButtonTable, 0,
           GameControllerMappingInfo::MAX_BUTTON_TABLE_SIZE *
               sizeof(Paddleboat_Controller_Mapping_File_Button_Entry));
    const uint32_t entryCount =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pb_test_button_start));
    EXPECT_EQ(entryCount, 1);
    memcpy(
        testButtonTable, pb_test_button_start,
        entryCount * sizeof(Paddleboat_Controller_Mapping_File_Button_Entry));
    // Init string table
    memset(testStringTable, 0,
           GameControllerMappingInfo::MAX_STRING_TABLE_SIZE *
               sizeof(Paddleboat_Controller_Mapping_File_String_Entry));
    const uint32_t stringEntryCount =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pbtest_buttonstringstart));
    EXPECT_EQ(stringEntryCount, 3);
    memcpy(testStringTable, pbtest_buttonstringstart,
           stringEntryCount *
               sizeof(Paddleboat_Controller_Mapping_File_String_Entry));
    // Init remap tables
    IndexTableRemap
        stringTableRemap[GameControllerMappingInfo::MAX_STRING_TABLE_SIZE];
    IndexTableRemap
        buttonTableRemap[GameControllerMappingInfo::MAX_BUTTON_TABLE_SIZE];
    memset(stringTableRemap, 0,
           GameControllerMappingInfo::MAX_STRING_TABLE_SIZE *
               sizeof(IndexTableRemap));
    memset(buttonTableRemap, 0,
           GameControllerMappingInfo::MAX_BUTTON_TABLE_SIZE *
               sizeof(IndexTableRemap));

    // Test 1, have to merge string table first
    uint32_t currentStringEntryCount = stringEntryCount;
    const uint32_t strAdd1Count =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pb_test_buttonstr_add1));
    errorCode = GameControllerMappingUtils::mergeStringTable(
        pb_test_buttonstr_add1, strAdd1Count, testStringTable,
        &currentStringEntryCount,
        GameControllerMappingInfo::MAX_STRING_TABLE_SIZE, stringTableRemap);
    EXPECT_EQ(errorCode, PADDLEBOAT_NO_ERROR);
    EXPECT_EQ(stringTableRemap[0].newIndex,
              3);  // index 0 old table, 3 new table

    // Should append one button entry and remap its button and string indices
    uint32_t currentEntryCount = entryCount;
    const uint32_t add1Count =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pb_test_button_add1));
    errorCode = GameControllerMappingUtils::mergeButtonTable(
        pb_test_button_add1, add1Count, testButtonTable, &currentEntryCount,
        GameControllerMappingInfo::MAX_BUTTON_TABLE_SIZE, stringTableRemap,
        buttonTableRemap);
    EXPECT_EQ(errorCode, PADDLEBOAT_NO_ERROR);
    EXPECT_EQ(currentEntryCount, 2);
    EXPECT_EQ(buttonTableRemap[0].newIndex,
              1);  // index 0 old table, 1 new table
    EXPECT_EQ(testButtonTable[0].buttonNameStringTableIndex, 2);
    EXPECT_EQ(testButtonTable[0].buttonMapping[0], AKEYCODE_DPAD_UP);
    EXPECT_EQ(testButtonTable[1].buttonNameStringTableIndex, 3);
    EXPECT_EQ(testButtonTable[1].buttonMapping[0], AKEYCODE_DPAD_DOWN);

    // Test 2, reset remap tables and merge string table first
    memset(stringTableRemap, 0,
           GameControllerMappingInfo::MAX_STRING_TABLE_SIZE *
               sizeof(IndexTableRemap));
    memset(buttonTableRemap, 0,
           GameControllerMappingInfo::MAX_BUTTON_TABLE_SIZE *
               sizeof(IndexTableRemap));
    const uint32_t strAdd2Count =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pb_test_buttonstr_add2));
    errorCode = GameControllerMappingUtils::mergeStringTable(
        pb_test_buttonstr_add2, strAdd2Count, testStringTable,
        &currentStringEntryCount,
        GameControllerMappingInfo::MAX_STRING_TABLE_SIZE, stringTableRemap);
    EXPECT_EQ(errorCode, PADDLEBOAT_NO_ERROR);
    EXPECT_EQ(stringTableRemap[0].newIndex,
              2);  // index 0 old table, 2 new table
    EXPECT_EQ(stringTableRemap[1].newIndex,
              4);  // index 1 old table, 4 new table

    // Should append one button entry and remap its button and string indices
    // and ignore a duplicate
    const uint32_t add2Count =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pb_test_button_add2));
    errorCode = GameControllerMappingUtils::mergeButtonTable(
        pb_test_button_add2, add2Count, testButtonTable, &currentEntryCount,
        GameControllerMappingInfo::MAX_BUTTON_TABLE_SIZE, stringTableRemap,
        buttonTableRemap);
    EXPECT_EQ(errorCode, PADDLEBOAT_NO_ERROR);
    EXPECT_EQ(currentEntryCount, 3);
    EXPECT_EQ(buttonTableRemap[0].newIndex,
              0);  // index 0 old table, 0 new table
    EXPECT_EQ(buttonTableRemap[1].newIndex,
              2);  // index 1 old table, 2 new table
    EXPECT_EQ(testButtonTable[0].buttonNameStringTableIndex, 2);
    EXPECT_EQ(testButtonTable[0].buttonMapping[0], AKEYCODE_DPAD_UP);
    EXPECT_EQ(testButtonTable[1].buttonNameStringTableIndex, 3);
    EXPECT_EQ(testButtonTable[1].buttonMapping[0], AKEYCODE_DPAD_DOWN);
    EXPECT_EQ(testButtonTable[2].buttonNameStringTableIndex, 4);
    EXPECT_EQ(testButtonTable[2].buttonMapping[0], AKEYCODE_DPAD_LEFT);

    // Test 3, reset remap tables and merge string table first
    memset(stringTableRemap, 0,
           GameControllerMappingInfo::MAX_STRING_TABLE_SIZE *
               sizeof(IndexTableRemap));
    memset(buttonTableRemap, 0,
           GameControllerMappingInfo::MAX_BUTTON_TABLE_SIZE *
               sizeof(IndexTableRemap));
    const uint32_t strAdd3Count =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pb_test_buttonstr_add3));
    errorCode = GameControllerMappingUtils::mergeStringTable(
        pb_test_buttonstr_add3, strAdd3Count, testStringTable,
        &currentStringEntryCount,
        GameControllerMappingInfo::MAX_STRING_TABLE_SIZE, stringTableRemap);
    EXPECT_EQ(errorCode, PADDLEBOAT_NO_ERROR);
    EXPECT_EQ(stringTableRemap[0].newIndex,
              5);  // index 0 old table, 5 new table

    // Should return no free space error, reuse add1 since the string table
    // remap counts as a 'new' button
    currentEntryCount = GameControllerMappingInfo::MAX_BUTTON_TABLE_SIZE;
    const uint32_t add3Count =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pb_test_button_add1));
    errorCode = GameControllerMappingUtils::mergeButtonTable(
        pb_test_button_add1, add3Count, testButtonTable, &currentEntryCount,
        GameControllerMappingInfo::MAX_BUTTON_TABLE_SIZE, stringTableRemap,
        buttonTableRemap);
    EXPECT_EQ(errorCode, PADDLEBOAT_ERROR_FEATURE_NOT_SUPPORTED);
}

// Controller file merge test
// ===================================================================== Test
// data

const Paddleboat_Controller_Mapping_File_Controller_Entry pbtest_ctrl_base[] = {
    {16, 00, 0x0010, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 1, 0, 0, 0},
    {16, 00, 0x0018, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 1, 0, 0, 0},
    {16, 18, 0x0020, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 2, 0, 0, 0},
    {19, 00, 0x0020, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 2, 0, 0, 0},
    {16, 00, 0x0020, 0x0004, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 1, 0, 0, 0},
    {16, 00, 0x0030, 0x0001, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 1, 0, 0,
     0}};

const Paddleboat_Controller_Mapping_File_Controller_Entry pbtest_ctrl_file[] = {
    {21, 00, 0x0020, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 0, 0, 0, 0},
    {24, 00, 0x0004, 0x0001, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 1, 0, 0,
     0}};

/* post-merge should look like:
const Paddleboat_Controller_Mapping_File_Controller_Entry pbtest_ctrl_merge[] =
{ { 24, 00, 0x0004, 0x0001, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 2, 1, 0, 0 }
        { 16, 00, 0x0010, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 1, 0,
0, 0 }, { 16, 00, 0x0018, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 1, 0,
0, 0 }, { 16, 18, 0x0020, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 2, 0,
0, 0 }, { 19, 20, 0x0020, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 2, 0,
0, 0 }, { 21, 00, 0x0020, 0x0002, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 1, 1,
0, 0 }, { 16, 00, 0x0020, 0x0004, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 1, 0,
0, 0 }, { 16, 00, 0x0030, 0x0001, PADDLEBOAT_CONTROLLER_LAYOUT_STANDARD, 1, 0,
0, 0 }
};

 */

const Paddleboat_Controller_Mapping_File_Axis_Entry pbtest_axis_base[] = {
    {1,  // "Generic_Axis"
     {
         /* LX */ AMOTION_EVENT_AXIS_X,
         /* LY */ AMOTION_EVENT_AXIS_Y,
         /* RX */ AMOTION_EVENT_AXIS_Z,
         /* RY */ AMOTION_EVENT_AXIS_RZ,
         /* L1 */ PADDLEBOAT_AXIS_IGNORED,
         /* L2 */ AMOTION_EVENT_AXIS_BRAKE,
         /* R1 */ PADDLEBOAT_AXIS_IGNORED,
         /* R2 */ AMOTION_EVENT_AXIS_GAS,
         /* HX */ AMOTION_EVENT_AXIS_HAT_X,
         /* HY */ AMOTION_EVENT_AXIS_HAT_Y,
     },
     {
         /* LX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* LY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L2 */ PADDLEBOAT_AXIS_BUTTON_L2,
         /* R1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R2 */ PADDLEBOAT_AXIS_BUTTON_R2,
         /* HX */ PADDLEBOAT_AXIS_BUTTON_DPAD_RIGHT,
         /* HY */ PADDLEBOAT_AXIS_BUTTON_DPAD_DOWN,
     },
     {
         /* LX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* LY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L2 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R2 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* HX */ PADDLEBOAT_AXIS_BUTTON_DPAD_LEFT,
         /* HY */ PADDLEBOAT_AXIS_BUTTON_DPAD_UP,
     },
     0},
    {2,  // "Silly_Axis"
     {
         /* LX */ AMOTION_EVENT_AXIS_Z,
         /* LY */ AMOTION_EVENT_AXIS_Y,
         /* RX */ AMOTION_EVENT_AXIS_X,
         /* RY */ AMOTION_EVENT_AXIS_RZ,
         /* L1 */ PADDLEBOAT_AXIS_IGNORED,
         /* L2 */ AMOTION_EVENT_AXIS_BRAKE,
         /* R1 */ PADDLEBOAT_AXIS_IGNORED,
         /* R2 */ AMOTION_EVENT_AXIS_GAS,
         /* HX */ AMOTION_EVENT_AXIS_HAT_X,
         /* HY */ AMOTION_EVENT_AXIS_HAT_Y,
     },
     {
         /* LX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* LY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L2 */ PADDLEBOAT_AXIS_BUTTON_L2,
         /* R1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R2 */ PADDLEBOAT_AXIS_BUTTON_R2,
         /* HX */ PADDLEBOAT_AXIS_BUTTON_DPAD_RIGHT,
         /* HY */ PADDLEBOAT_AXIS_BUTTON_DPAD_DOWN,
     },
     {
         /* LX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* LY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L2 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R2 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* HX */ PADDLEBOAT_AXIS_BUTTON_DPAD_LEFT,
         /* HY */ PADDLEBOAT_AXIS_BUTTON_DPAD_UP,
     },
     0}};

const Paddleboat_Controller_Mapping_File_Axis_Entry pbtest_axis_file[] = {
    {2,  // "Silly_Axis"
     {
         /* LX */ AMOTION_EVENT_AXIS_Z,
         /* LY */ AMOTION_EVENT_AXIS_Y,
         /* RX */ AMOTION_EVENT_AXIS_X,
         /* RY */ AMOTION_EVENT_AXIS_RZ,
         /* L1 */ PADDLEBOAT_AXIS_IGNORED,
         /* L2 */ AMOTION_EVENT_AXIS_BRAKE,
         /* R1 */ PADDLEBOAT_AXIS_IGNORED,
         /* R2 */ AMOTION_EVENT_AXIS_GAS,
         /* HX */ AMOTION_EVENT_AXIS_HAT_X,
         /* HY */ AMOTION_EVENT_AXIS_HAT_Y,
     },
     {
         /* LX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* LY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L2 */ PADDLEBOAT_AXIS_BUTTON_L2,
         /* R1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R2 */ PADDLEBOAT_AXIS_BUTTON_R2,
         /* HX */ PADDLEBOAT_AXIS_BUTTON_DPAD_RIGHT,
         /* HY */ PADDLEBOAT_AXIS_BUTTON_DPAD_DOWN,
     },
     {
         /* LX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* LY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L2 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R2 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* HX */ PADDLEBOAT_AXIS_BUTTON_DPAD_LEFT,
         /* HY */ PADDLEBOAT_AXIS_BUTTON_DPAD_UP,
     },
     0},
    {4,  // "Foo_Axis"
     {
         /* LX */ AMOTION_EVENT_AXIS_Y,
         /* LY */ AMOTION_EVENT_AXIS_Z,
         /* RX */ AMOTION_EVENT_AXIS_X,
         /* RY */ AMOTION_EVENT_AXIS_RZ,
         /* L1 */ PADDLEBOAT_AXIS_IGNORED,
         /* L2 */ AMOTION_EVENT_AXIS_BRAKE,
         /* R1 */ PADDLEBOAT_AXIS_IGNORED,
         /* R2 */ AMOTION_EVENT_AXIS_GAS,
         /* HX */ AMOTION_EVENT_AXIS_HAT_X,
         /* HY */ AMOTION_EVENT_AXIS_HAT_Y,
     },
     {
         /* LX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* LY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L2 */ PADDLEBOAT_AXIS_BUTTON_L2,
         /* R1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R2 */ PADDLEBOAT_AXIS_BUTTON_R2,
         /* HX */ PADDLEBOAT_AXIS_BUTTON_DPAD_RIGHT,
         /* HY */ PADDLEBOAT_AXIS_BUTTON_DPAD_DOWN,
     },
     {
         /* LX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* LY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RX */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* RY */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* L2 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R1 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* R2 */ PADDLEBOAT_AXIS_BUTTON_IGNORED,
         /* HX */ PADDLEBOAT_AXIS_BUTTON_DPAD_LEFT,
         /* HY */ PADDLEBOAT_AXIS_BUTTON_DPAD_UP,
     },
     0}};

const Paddleboat_Controller_Mapping_File_Button_Entry pbtest_button_base[] = {
    {3,  // "XB_Button"
     {/* UP     */ AKEYCODE_DPAD_UP,
      /* LEFT   */ AKEYCODE_DPAD_LEFT,
      /* DOWN   */ AKEYCODE_DPAD_DOWN,
      /* RIGHT  */ AKEYCODE_DPAD_RIGHT,
      /* A      */ AKEYCODE_BUTTON_A,
      /* B      */ AKEYCODE_BUTTON_B,
      /* X      */ AKEYCODE_BUTTON_X,
      /* Y      */ AKEYCODE_BUTTON_Y,
      /* L1     */ AKEYCODE_BUTTON_L1,
      /* L2     */ AKEYCODE_BUTTON_L2,
      /* L3     */ AKEYCODE_BUTTON_THUMBL,
      /* R1     */ AKEYCODE_BUTTON_R1,
      /* R2     */ AKEYCODE_BUTTON_R2,
      /* R3     */ AKEYCODE_BUTTON_THUMBR,
      /* SELECT */ AKEYCODE_BUTTON_SELECT,
      /* START  */ AKEYCODE_BUTTON_START,
      /* SYSTEM */ AKEYCODE_BUTTON_MODE,
      /* TOUCHP */ PADDLEBOAT_BUTTON_IGNORED,
      /* AUX1   */ AKEYCODE_MEDIA_RECORD,
      /* AUX2   */ PADDLEBOAT_BUTTON_IGNORED,
      /* AUX3   */ PADDLEBOAT_BUTTON_IGNORED,
      /* AUX4   */ PADDLEBOAT_BUTTON_IGNORED}}};

const Paddleboat_Controller_Mapping_File_Button_Entry pbtest_button_file[] = {
    {5,  // "Foo_Button"
     {/* UP     */ AKEYCODE_DPAD_DOWN,
      /* LEFT   */ AKEYCODE_DPAD_LEFT,
      /* DOWN   */ AKEYCODE_DPAD_UP,
      /* RIGHT  */ AKEYCODE_DPAD_RIGHT,
      /* A      */ AKEYCODE_BUTTON_A,
      /* B      */ AKEYCODE_BUTTON_B,
      /* X      */ AKEYCODE_BUTTON_X,
      /* Y      */ AKEYCODE_BUTTON_Y,
      /* L1     */ AKEYCODE_BUTTON_L1,
      /* L2     */ AKEYCODE_BUTTON_L2,
      /* L3     */ AKEYCODE_BUTTON_THUMBL,
      /* R1     */ AKEYCODE_BUTTON_R1,
      /* R2     */ AKEYCODE_BUTTON_R2,
      /* R3     */ AKEYCODE_BUTTON_THUMBR,
      /* SELECT */ AKEYCODE_BUTTON_SELECT,
      /* START  */ AKEYCODE_BUTTON_START,
      /* SYSTEM */ AKEYCODE_BUTTON_MODE,
      /* TOUCHP */ PADDLEBOAT_BUTTON_IGNORED,
      /* AUX1   */ AKEYCODE_MEDIA_RECORD,
      /* AUX2   */ PADDLEBOAT_BUTTON_IGNORED,
      /* AUX3   */ PADDLEBOAT_BUTTON_IGNORED,
      /* AUX4   */ PADDLEBOAT_BUTTON_IGNORED}}};

const Paddleboat_Controller_Mapping_File_String_Entry pbtest_string_base[] = {
    {"None"},          // 00
    {"Generic_Axis"},  // 01
    {"Silly_Axis"},    // 02
    {"XB_Button"}      // 03
};

const Paddleboat_Controller_Mapping_File_String_Entry pbtest_string_file[] = {
    {"None"},          // 00
    {"Generic_Axis"},  // 01
    {"Silly_Axis"},    // 02
    {"XB_Button"},     // 03
    {"Foo_Axis"},      // 04
    {"Foo_Button"}     // 05
};

// Test controller file merge
TEST(PaddleboatValidateControllerFileMerge, Validity) {
    // Initialize the 'base' data
    GameControllerMappingInfo mappingInfo;
    mappingInfo.mAxisTableEntryCount =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pbtest_axis_base));
    memcpy(&mappingInfo.mAxisTable[0], pbtest_axis_base,
           mappingInfo.mAxisTableEntryCount *
               sizeof(Paddleboat_Controller_Mapping_File_Axis_Entry));
    mappingInfo.mButtonTableEntryCount =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pbtest_button_base));
    memcpy(&mappingInfo.mButtonTable[0], pbtest_button_base,
           mappingInfo.mButtonTableEntryCount *
               sizeof(Paddleboat_Controller_Mapping_File_Button_Entry));
    mappingInfo.mControllerTableEntryCount =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pbtest_ctrl_base));
    memcpy(&mappingInfo.mControllerTable[0], pbtest_ctrl_base,
           mappingInfo.mControllerTableEntryCount *
               sizeof(Paddleboat_Controller_Mapping_File_Controller_Entry));
    mappingInfo.mStringTableEntryCount =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pbtest_string_base));
    memcpy(&mappingInfo.mStringTable[0], pbtest_string_base,
           mappingInfo.mStringTableEntryCount *
               sizeof(Paddleboat_Controller_Mapping_File_String_Entry));

    // Generate a fake 'file'
    const uint32_t axisTableCount =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pbtest_axis_file));
    const uint32_t buttonTableCount =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pbtest_button_file));
    const uint32_t controllerTableCount =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pbtest_ctrl_file));
    const uint32_t stringTableCount =
        static_cast<const uint32_t>(ARRAY_COUNTOF(pbtest_string_file));
    const size_t axisTableSize =
        axisTableCount * sizeof(Paddleboat_Controller_Mapping_File_Axis_Entry);
    const size_t buttonTableSize =
        buttonTableCount *
        sizeof(Paddleboat_Controller_Mapping_File_Button_Entry);
    const size_t controllerTableSize =
        controllerTableCount *
        sizeof(Paddleboat_Controller_Mapping_File_Controller_Entry);
    const size_t stringTableSize =
        stringTableCount *
        sizeof(Paddleboat_Controller_Mapping_File_String_Entry);
    const size_t headerSize = sizeof(Paddleboat_Controller_Mapping_File_Header);
    const size_t fileSize = headerSize + axisTableSize + buttonTableSize +
                            controllerTableSize + stringTableSize;
    uint8_t *basePtr = reinterpret_cast<uint8_t *>(malloc(fileSize));
    memset(basePtr, 0, fileSize);
    Paddleboat_Controller_Mapping_File_Header *fileHeader =
        reinterpret_cast<Paddleboat_Controller_Mapping_File_Header *>(basePtr);
    fileHeader->fileIdentifier = PADDLEBOAT_MAPPING_FILE_IDENTIFIER;
    fileHeader->libraryMinimumVersion = 0x010200;
    fileHeader->axisTableEntryCount = axisTableCount;
    fileHeader->buttonTableEntryCount = buttonTableCount;
    fileHeader->controllerTableEntryCount = controllerTableCount;
    fileHeader->stringTableEntryCount = stringTableCount;
    uint64_t offset = headerSize;
    fileHeader->axisTableOffset = offset;
    memcpy(&basePtr[offset], pbtest_axis_file, axisTableSize);
    offset += axisTableSize;
    fileHeader->buttonTableOffset = offset;
    memcpy(&basePtr[offset], pbtest_button_file, buttonTableSize);
    offset += buttonTableSize;
    fileHeader->controllerTableOffset = offset;
    memcpy(&basePtr[offset], pbtest_ctrl_file, controllerTableSize);
    offset += controllerTableSize;
    fileHeader->stringTableOffset = offset;
    memcpy(&basePtr[offset], pbtest_string_file, stringTableSize);

    Paddleboat_ErrorCode errorCode =
        GameControllerMappingUtils::mergeControllerRemapData(
            fileHeader, fileSize, mappingInfo);
    EXPECT_EQ(errorCode, PADDLEBOAT_NO_ERROR);
    EXPECT_EQ(mappingInfo.mAxisTableEntryCount, 3);
    EXPECT_EQ(mappingInfo.mButtonTableEntryCount, 2);
    EXPECT_EQ(mappingInfo.mControllerTableEntryCount, 8);
    EXPECT_EQ(mappingInfo.mStringTableEntryCount, 6);
    // First entry should be the second entry from the 'file' with remapped
    // button and axis
    EXPECT_EQ(mappingInfo.mControllerTable[0].vendorId,
              pbtest_ctrl_file[1].vendorId);
    EXPECT_EQ(mappingInfo.mControllerTable[0].productId,
              pbtest_ctrl_file[1].productId);
    EXPECT_EQ(mappingInfo.mControllerTable[0].axisTableIndex, 2);
    EXPECT_EQ(mappingInfo.mControllerTable[0].buttonTableIndex, 1);
    // Second entry should be the first entry from the 'base', unmodified
    EXPECT_EQ(
        ControllerEqual(mappingInfo.mControllerTable[1], pbtest_ctrl_base[0]),
        true);
    // Fifth entry should be a 'patched' version of the fourth 'base' entry
    EXPECT_EQ(mappingInfo.mControllerTable[4].vendorId,
              pbtest_ctrl_base[3].vendorId);
    EXPECT_EQ(mappingInfo.mControllerTable[4].productId,
              pbtest_ctrl_base[3].productId);
    EXPECT_EQ(mappingInfo.mControllerTable[4].minimumEffectiveApiLevel,
              pbtest_ctrl_base[3].minimumEffectiveApiLevel);
    EXPECT_EQ(mappingInfo.mControllerTable[4].maximumEffectiveApiLevel, 20);
    // Sixth entry should be the first entry from the 'file' with remapped
    // button
    EXPECT_EQ(mappingInfo.mControllerTable[5].vendorId,
              pbtest_ctrl_file[0].vendorId);
    EXPECT_EQ(mappingInfo.mControllerTable[5].productId,
              pbtest_ctrl_file[0].productId);
    EXPECT_EQ(mappingInfo.mControllerTable[5].axisTableIndex, 1);
    EXPECT_EQ(mappingInfo.mControllerTable[5].buttonTableIndex, 1);
    EXPECT_EQ(mappingInfo.mControllerTable[5].minimumEffectiveApiLevel,
              pbtest_ctrl_file[0].minimumEffectiveApiLevel);
    EXPECT_EQ(mappingInfo.mControllerTable[5].maximumEffectiveApiLevel,
              pbtest_ctrl_file[0].maximumEffectiveApiLevel);
    free(basePtr);
}
