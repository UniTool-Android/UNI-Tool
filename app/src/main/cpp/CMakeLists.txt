cmake_minimum_required(VERSION 3.22.1)
project(unitool)

add_library(unitool SHARED unitool_native.c)

find_library(log-lib log)

target_link_libraries(unitool ${log-lib})

target_compile_options(unitool PRIVATE -Wall -Wextra -O2)
