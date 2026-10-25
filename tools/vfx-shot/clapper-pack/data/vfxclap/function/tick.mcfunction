execute store result score #g vfxclap run time query gametime
scoreboard players operation #g vfxclap %= #mod vfxclap
execute store result bossbar vfxclap:tick value run scoreboard players get #g vfxclap
bossbar set vfxclap:tick players @a
