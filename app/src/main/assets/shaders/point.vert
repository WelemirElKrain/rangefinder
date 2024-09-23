#version 100
precision mediump float;

attribute vec4 a_Position;
uniform mat4 u_ModelViewProjection;

void main() {
    gl_Position = u_ModelViewProjection * a_Position;
    gl_PointSize = 25.0; // Размер точки
}