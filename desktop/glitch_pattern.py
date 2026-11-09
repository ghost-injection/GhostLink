"""Bounded, normalized rectangular interference; independent of UI and user data."""
import random

class GlitchPattern:
    def __init__(self, rng=None):
        self.rng = rng or random.Random()
        self.blocks = []
        self.next = 1.2

    def burst(self, count=18):
        for _ in range(min(count, 32)):
            width = self.rng.uniform(.025, .205)
            height = self.rng.uniform(.004, .039)
            self.blocks.append(dict(x=self.rng.uniform(0, 1-width),
                                    y=self.rng.uniform(.06, .94-height),
                                    w=width, h=height, age=0,
                                    life=self.rng.uniform(.28, .93),
                                    light=self.rng.randrange(4) == 0))
        self.blocks = self.blocks[-32:]

    def step(self, dt, enabled=True, ambient=True):
        if not enabled:
            self.blocks.clear()
            return
        for block in self.blocks:
            block['age'] += dt
        self.blocks = [b for b in self.blocks if b['age'] < b['life']]
        self.next -= dt
        if self.next <= 0:
            if ambient:
                self.burst(12)
            self.next = self.rng.uniform(2, 6)
