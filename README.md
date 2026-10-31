# SW x CA Compat

Compatibility patch that keeps **Superb Warfare** vehicles and turrets attached to moving
**Create Aeronautics** airships (Sable sub-levels).

## Features

- Turrets and vehicles stay fixed on the deck with no drift and no client-side lag
- Parked entities are locked in place, including on tilted or inverted ships
- Wheeled vehicles can be driven around the deck while still carried by the ship
- Driving off the ship releases the vehicle so it falls naturally
- Self-propelled artillery (PLZ-05, FH-77BW) remains drivable
- Entities that spawn slightly above the deck settle first, then bind

## Requirements

- Minecraft 1.21.1
- NeoForge
- Sable 2.0.x
- Create Aeronautics (1.21.1)
- Superb Warfare 0.8.9.x (1.21.1)

## Known limitations

Deck tilt is not applied visually. Superb Warfare calculates aim, ballistics and the
driving camera in world space, so rotating the models to match the deck would break
aiming and desynchronise the driving view. Level entities keep shooting and driving
correct.

## Build

```
./gradlew build
```

The output jar is written to `build/libs/`.

## License

MIT
