export type Axis = 'x' | 'y' | 'z'
export type Angle = -45 | -22.5 | 0 | 22.5 | 45

export const VALID_ANGLES: number[] = [-45, -22.5, 0, 22.5, 45]
export const VALID_AXES = ['x', 'y', 'z'] as const
