cmake_minimum_required(VERSION 3.22.1)

project("termux-render")

add_library(${CMAKE_PROJECT_NAME} SHARED
        # List C/C++ source files with relative paths to this CMakeLists.txt.
        tlog.c
        buffer.c
        render.c)

target_link_libraries(${CMAKE_PROJECT_NAME}
        # List libraries link to the target library
        android log m
)
install(TARGETS ${CMAKE_PROJECT_NAME} LIBRARY)
install(FILES
        ${CMAKE_SOURCE_DIR}/include/buffer.h
        ${CMAKE_SOURCE_DIR}/include/render.h
        ${CMAKE_SOURCE_DIR}/include/tlog.h
        DESTINATION include/termux/render
)

add_executable(termux-render-sample main.c)
target_link_libraries(termux-render-sample
        ${CMAKE_PROJECT_NAME}
        )
set_target_properties(termux-render-sample PROPERTIES LINK_FLAGS "-Wl,--allow-shlib-undefined")
install(TARGETS termux-render-sample RUNTIME)
