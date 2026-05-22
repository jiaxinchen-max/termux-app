cmake_minimum_required(VERSION 3.22.1)

project("termux-render")

include(GNUInstallDirs)

set(TERMUX_RENDER_ASSETS_INSTALL_DIR "${CMAKE_INSTALL_DATADIR}/termux-render/assets")
set(TERMUX_RENDER_ASSETS_DIR "" CACHE PATH "Runtime path for termux-render assets")
if(NOT TERMUX_RENDER_ASSETS_DIR)
        set(TERMUX_RENDER_ASSETS_DIR "${CMAKE_INSTALL_FULL_DATADIR}/termux-render/assets")
endif()

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
install(DIRECTORY
        ${CMAKE_SOURCE_DIR}/assets/
        DESTINATION ${TERMUX_RENDER_ASSETS_INSTALL_DIR}
)

add_executable(termux-render-sample main.c)
target_compile_definitions(termux-render-sample PRIVATE
        TERMUX_RENDER_ASSETS_DIR="${TERMUX_RENDER_ASSETS_DIR}")
target_link_libraries(termux-render-sample
        ${CMAKE_PROJECT_NAME}
        )
set_target_properties(termux-render-sample PROPERTIES LINK_FLAGS "-Wl,--allow-shlib-undefined")
install(TARGETS termux-render-sample RUNTIME)
