cmake_minimum_required(VERSION 3.10)
project(m68kmake_builder LANGUAGES C)
add_executable(m68kmake m68kmake.c)

include(GNUInstallDirs)
install(TARGETS m68kmake RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR})
