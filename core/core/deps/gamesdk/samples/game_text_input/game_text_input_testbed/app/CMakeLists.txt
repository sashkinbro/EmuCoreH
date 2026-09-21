cmake_minimum_required(VERSION 3.4.1)

project(game-input)

include_directories(src/main/cpp)

# Find the Game Text Input package, exposed via Prefab.
find_package(game-text-input REQUIRED CONFIG)

add_library(${CMAKE_PROJECT_NAME} SHARED
            src/main/cpp/game-input.cpp
            )

target_link_libraries(${CMAKE_PROJECT_NAME}
        game-text-input::game-text-input
        android log)
